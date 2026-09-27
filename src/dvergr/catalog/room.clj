(ns dvergr.catalog.room
  "Workflows defined in a room (doc/room-workflows.md): a bundle of files in
   the room's repository, `workflows/<name>/`, that the catalog runs like its
   own workflows.

     workflow.edn   {:title :doc :task \"… {param} …\" :params {…} :profile
                     :capture [\"/out\"] :timeout-ms}
     checker.clj    a namespace defining (check {:files :fetched :judgements
                    :params :gold}), and optionally (judge-requests {…}) →
                    [{:id :prompt}] for the `:judge` model
                    → {:checks {k bool} :reward 0..1}
     gold.edn       optional: the reference facts the checker scores against
     fixtures/…     the files each attempt's world starts with (at the root:
                    fixtures/docs/a.md → /docs/a.md)
     calibration.edn optional: {:reference {path text} :fetched {url body}
                    :damaged {name {:files {path text} :fetched {…} :loses
                    [check …]}}}, how the checker earns
                    trust (`calibrate`, `promote!`)

   The checker runs in SCI with no effects: it is given the files the attempt
   left under the captured directories and returns a verdict. It cannot read
   the room, the network or its own workspace. An unpromoted bundle's
   verifier is `:ad-hoc` on every receipt."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.workflow :as workflow]
            [dvergr.catalog.workspace :as ws]
            [dvergr.effects :as effects]
            [dvergr.io.frozen-web :as frozen-web]
            [dvergr.sandbox.ns.io :as sandbox-io]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.sandbox :as sandbox]
            [hasch.core :as hasch]
            [malli.core :as m]
            [malli.error :as me]
            [sci.core :as sci]))

(def Definition
  "workflow.edn"
  [:map {:closed true}
   [:title :string]
   [:doc {:optional true} :string]
   [:task :string]
   [:params {:optional true} [:map-of :keyword :any]]
   [:profile {:optional true} :string]
   [:capture {:optional true} [:vector [:re #"^/[^.]*$"]]]
   ;; give the checker the pages the attempt fetched (`:fetched {url body}`)
   [:fetched {:optional true} :boolean]
   ;; a model that answers the checker's `judge-requests` (e.g. is a new find
   ;; relevant?); part of what a score means, so part of the verifier's basis
   [:judge {:optional true} [:map [:model :string] [:max-requests {:optional true} [:int {:min 1 :max 50}]]]]
   ;; :frozen: the attempt's web is web.edn (`freeze`), not the internet
   [:web {:optional true} [:enum :live :frozen]]
   [:timeout-ms {:optional true} [:int {:min 1000}]]])

(def Verdict
  "What a checker returns."
  [:map [:checks [:map-of :keyword :boolean]] [:reward [:double {:min 0.0 :max 1.0}]]])

(def ^:private checker-timeout-ms 20000)

;; ---------------------------------------------------------------------------
;; A bundle from files
;; ---------------------------------------------------------------------------

(defn- read-edn [files path]
  (when-let [text (get files path)]
    (try (edn/read-string text)
         (catch Exception e
           (throw (ex-info (str path ": not EDN (" (.getMessage e) ")")
                           {:type ::invalid-bundle :file path}))))))

(defn check-files
  "Problems with a bundle's files (`{relative-path text}`), as strings; empty
   when it is well formed."
  [files]
  (let [definition (try (read-edn files "workflow.edn") (catch Exception e {::error (ex-message e)}))]
    (cond-> []
      (not (contains? files "workflow.edn")) (conj "workflow.edn is missing")
      (::error definition) (conj (::error definition))
      (and (map? definition) (not (::error definition)) (not (m/validate Definition definition)))
      (conj (str "workflow.edn: " (pr-str (me/humanize (m/explain Definition definition)))))
      (not (contains? files "checker.clj")) (conj "checker.clj is missing")
      (not (some #(str/starts-with? % "fixtures/") (keys files)))
      (conj "fixtures/ is empty: every attempt's world would start with nothing")

      (and (map? definition) (= :frozen (:web definition)) (not (contains? files "web.edn")))
      (conj "workflow.edn says :web :frozen but web.edn is missing"))))

(defn bundle
  "A bundle from its files (`{relative-path text}`), or throws with its
   problems. `:id` is the bundle's content id: any change is a new bundle,
   so a Scorecard never mixes versions."
  [name files]
  (when-let [problems (seq (check-files files))]
    (throw (ex-info (str "Workflow bundle " name " is not well formed: " (str/join "; " problems))
                    {:type ::invalid-bundle :problems (vec problems)})))
  (let [definition (read-edn files "workflow.edn")]
    {:name name
     :definition definition
     :checker (get files "checker.clj")
     :gold (read-edn files "gold.edn")
     :calibration (read-edn files "calibration.edn")
     :web (when (= :frozen (:web definition)) (read-edn files "web.edn"))
     :fixtures (into (sorted-map)
                     (keep (fn [[path text]]
                             (when (str/starts-with? path "fixtures/")
                               [(subs path (count "fixtures")) text])))
                     files)
     :id (hasch/uuid [:dvergr/room-workflow files])}))

(defn bundle-files
  "The files of `workflows/<name>/` in `room`'s workspace, by path relative to
   it; throws when there are none."
  [room name]
  (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
    (throw (ex-info (str "A workflow name is lowercase letters, digits and dashes: " name)
                    {:type ::invalid-name :name name})))
  (let [dir (str "/workflows/" name)
        files (into {} (map (fn [[path text]] [(subs path (inc (count dir))) text]))
                    (ws/read-tree room dir))]
    (when (empty? files)
      (throw (ex-info (str "No workflow " name " in this room (expected " dir "/)")
                      {:type ::no-bundle :name name})))
    files))

(defn read-bundle
  "The bundle `workflows/<name>/` in `room`'s workspace."
  [room name]
  (bundle name (bundle-files room name)))

(defn list-bundles
  "The names of the workflow bundles in `room`'s workspace."
  [room]
  (->> (keys (ws/read-tree room "/workflows"))
       (keep #(second (re-find #"^/workflows/([^/]+)/workflow\.edn$" %)))
       sort vec))

;; ---------------------------------------------------------------------------
;; The checker, in SCI with no effects
;; ---------------------------------------------------------------------------

(defn- checker-ctx
  "A fresh interpreter with the base sandbox's safe core and nothing else:
   no namespaces that reach the world, and no `require` path (a checker is
   one namespace)."
  []
  (sandbox/create-base-ctx :load-fn (constantly nil)))

(defn- call-checker
  "Call `fname` of checker `source` on `input` in a fresh interpreter, bounded
   in time; `::absent` when the checker does not define it."
  [source fname input]
  (let [ctx (checker-ctx)
        run (future
              (let [{:keys [ns]} (sci/eval-string+ ctx source)
                    f (sci/eval-string* ctx (str "(ns-resolve '" ns " '" fname ")"))]
                (if f (@f input) ::absent)))
        result (deref run checker-timeout-ms ::timeout)]
    (when (= ::timeout result)
      (future-cancel run)
      (throw (ex-info (str "The checker did not finish within " checker-timeout-ms "ms")
                      {:type ::checker-timeout})))
    result))

(def JudgeRequests
  "What a checker's `judge-requests` returns: questions for the judge model."
  [:vector [:map [:id :string] [:prompt :string]]])

(defn judge-requests
  "The questions `source`'s `judge-requests` asks about `input` (nil when it
   defines none), validated and bounded by `max-requests`."
  [source input max-requests]
  (let [rs (call-checker source "judge-requests" input)]
    (when-not (= ::absent rs)
      (when-not (m/validate JudgeRequests (vec rs))
        (throw (ex-info (str "judge-requests returned no requests: " (pr-str (me/humanize (m/explain JudgeRequests (vec rs)))))
                        {:type ::invalid-judge-requests})))
      (vec (take max-requests rs)))))

(defn run-checker
  "Run `source`'s `check` on `input`, bounded in time; the verdict, validated."
  [source input]
  (let [verdict (call-checker source "check" input)]
    (when (= ::absent verdict)
      (throw (ex-info "checker.clj defines no `check`" {:type ::no-check})))
    (let [verdict (update verdict :reward #(some-> % double))]
      (when-not (m/validate Verdict verdict)
        (throw (ex-info (str "The checker returned no verdict: " (pr-str (me/humanize (m/explain Verdict verdict))))
                        {:type ::invalid-verdict})))
      verdict)))

;; ---------------------------------------------------------------------------
;; Calibration and promotion: how a checker earns trust
;; ---------------------------------------------------------------------------

(defn calibrate
  "Run the checker on the bundle's reference answer and damaged variants. The
   reference must pass every check and score highest; each variant must fail
   the checks it says it damages and score below the reference. Returns
   `{:ok? :reference verdict :damaged {name {:verdict :ok? :kept [check …]}}
   :problems [...]}`."
  [{:keys [checker gold calibration definition id]}]
  (let [params (:params definition)
        ;; judgements, like fetched pages, come from calibration.edn: no model
        run (fn [files fetched judgements]
              (run-checker checker {:files files :fetched (or fetched (:fetched calibration) {})
                                    :judgements (or judgements (:judgements calibration) {})
                                    :gold gold :params params}))]
    (if-not (:reference calibration)
      {:ok? false :bundle (str id) :problems ["calibration.edn has no :reference answer"]}
      (let [reference (run (:reference calibration) nil nil)
            damaged (into (sorted-map)
                          (for [[k {:keys [files fetched judgements loses]}] (:damaged calibration)
                                :let [v (run files fetched judgements)
                                      kept (vec (filter #(get-in v [:checks %]) loses))]]
                            [k {:verdict v :kept kept
                                :ok? (boolean (and (empty? kept) (seq loses)
                                                   (< (:reward v) (:reward reference))))}]))
            problems (cond-> []
                       (not-every? true? (vals (:checks reference)))
                       (conj (str "the reference fails " (vec (keep (fn [[k v]] (when-not v k)) (:checks reference)))))
                       (empty? damaged) (conj "calibration.edn has no :damaged variants: nothing shows the checker notices damage")
                       :always (into (for [[k {:keys [ok? kept]}] damaged :when (not ok?)]
                                       (if (seq kept)
                                         (str (name k) " still passes " kept)
                                         (str (name k) " does not score below the reference")))))]
        {:ok? (empty? problems) :bundle (str id) :reference reference :damaged damaged :problems problems}))))

(defn- promotions-file []
  (java.io.File. (str ((requiring-resolve 'dvergr.substrate.paths/home))) "workflow-promotions.edn"))

(defn- promotions []
  (let [f (promotions-file)]
    (if (.exists f) (edn/read-string (slurp f)) {})))

(defn promoted?
  "Was bundle `id` promoted on this host?"
  [id]
  (contains? (promotions) (str id)))

(def ^:private promotion-lock (Object.))

(defn promote!
  "Promote bundle `b` of `room-slug` on this host: calibrate it now and record
   its content id when calibration holds. Host state under the state root,
   outside every workspace: sandbox code cannot write it. A changed bundle is a
   new id, promoted afresh."
  [b room-slug]
  (let [{:keys [ok?] :as result} (calibrate b)]
    (when ok?
      (locking promotion-lock
        (let [f (promotions-file)]
          (.mkdirs (.getParentFile f))
          (spit f (pr-str (assoc (promotions) (str (:id b))
                                 {:room (str room-slug) :name (:name b) :at (java.util.Date.)}))))))
    (assoc result :promoted? (boolean ok?))))

(defn tier
  "The trust tier of `b`'s verifier: `:room` once promoted, else `:ad-hoc`."
  [b]
  (if (promoted? (:id b)) :room :ad-hoc))

;; ---------------------------------------------------------------------------
;; As a catalog workflow: task, world setup, evaluator, environment, plan
;; ---------------------------------------------------------------------------

(defn task
  "The task text: the template with `{param}` filled from `params`."
  [{:keys [definition]} params]
  (reduce-kv (fn [t k v] (str/replace t (str "{" (name k) "}") (str v)))
             (:task definition) (merge (:params definition) params)))

(defn- verifier-id [{:keys [name]}]
  (keyword "room-workflow" name))

(defn world-setup
  "Installs the bundle's fixtures in each attempt's world (a workspace of its
   own, committed) and, for a frozen web, its pages as the world's web: every
   HTTP request in the world is answered from them. Its evidence is their
   digest."
  [{:keys [fixtures id web]}]
  (let [digest (str (hasch/uuid fixtures))
        transport (when web (frozen-web/transport web))
        web-id (when web (hasch/uuid [:room-workflow/web web]))]
    (evaluation/make-world-setup
     {:id :room-workflow/fixtures :version 1
      :basis (cond-> {:bundle (str id) :fixtures digest} web (assoc :web (str web-id)))
      :prepare (fn [{world :room}]
                 (ws/ensure-workspace! world)
                 (ws/seed! world fixtures)
                 (when web
                   (binding [ec/*execution-context* (:ctx world)]
                     (sandbox-io/install-http-fixture!
                      {:id web-id :transport transport :env {"BRAVE_API_KEY" "frozen-web"}})))
                 (cond-> {:fixtures digest :files (count fixtures)}
                   web (assoc :web (str web-id) :pages (count web))))})))

;; ---------------------------------------------------------------------------
;; Freezing: the web a live run saw, as a stable benchmark
;; ---------------------------------------------------------------------------

(defn- page-text
  "A fetched page as text: scripts, styles and tags removed, whitespace folded."
  [body]
  (-> (str body)
      (str/replace #"(?is)<(script|style|noscript)[^>]*>.*?</\1>" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"&nbsp;|&#160;" " ") (str/replace "&amp;" "&")
      (str/replace #"\s+" " ") str/trim))

(defn- page-title [body url]
  (or (some-> (re-find #"(?is)<title[^>]*>(.*?)</title>" (str body)) second page-text not-empty)
      url))

(defn frozen-pages
  "The web `fetched` maps (`{url body}`, e.g. from Attempts' evidence) show,
   as frozen pages `{url {:title :body}}`: https pages only, not search
   responses (frozen search runs over the pages), each at most 50,000
   characters of text."
  [fetched]
  (into (sorted-map)
        (for [m fetched [url body] m
              :when (and (re-matches #"https://[^\s?#]+" url)
                         (not (str/starts-with? url frozen-web/search-url)))
              :let [text (page-text body)]
              :when (seq text)]
          [url {:title (page-title body url) :body (subs text 0 (min (count text) 50000))}])))

(defn freeze
  "The files of `b`'s frozen variant: the same bundle with `pages` as its web
   (web.edn) and `:web :frozen`, so every attempt meets the same web. A new
   bundle: its own content id, calibrated and promoted on its own."
  [b files pages]
  (when (empty? pages)
    (throw (ex-info "Nothing to freeze: no fetched pages" {:type ::nothing-to-freeze})))
  (let [definition (-> (:definition b)
                       (assoc :web :frozen)
                       (update :title str " (frozen web)"))]
    (assoc files
           "workflow.edn" (pr-str definition)
           "web.edn" (pr-str pages))))

(def ^:private max-page-chars
  "A fetched page is kept to this many characters as evidence."
  200000)

(defn- bounded-pages
  "At most 40 pages, each as text (a page's markup can be most of it: the
   words a citation quotes may lie past any cut of the raw HTML), cut to
   `max-page-chars`: the evidence is stored with the Attempt."
  [pages]
  (into {} (map (fn [[u b]] (let [t (page-text b)] [u (subs t 0 (min (count t) max-page-chars))])))
        (take 40 pages)))

(defn- model-judge
  "Ask `model` one judge question: its answer as text (at most 500 characters)."
  [model]
  (fn [prompt]
    (let [{:keys [text error]} ((requiring-resolve 'dvergr.tools.llm-call/cheap-llm-call)
                                "Answer the question exactly as it asks, briefly." prompt
                                {:model model :max-tokens 200})]
      (if error
        (throw (ex-info (str "The judge failed: " error) {:type ::judge-failed}))
        (let [t (str/trim (str text))] (subs t 0 (min (count t) 500)))))))

(defn evaluator
  "The bundle's checker as an Evaluator: it captures the files under the
   bundle's `:capture` directories and runs the checker on them. `tier` is
   `:ad-hoc` unless the bundle was promoted. With a `:judge` in workflow.edn,
   the checker's `judge-requests` are answered by that model once, when the
   Attempt is observed, and kept as evidence (`:judgements {id answer}`, given
   to `check`); `judge-fn` replaces the model (tests)."
  [{:keys [definition checker gold id] :as b} {:keys [params tier judge-fn]}]
  (let [dirs (or (:capture definition) ["/out"])
        params (merge (:params definition) params)
        {judge-model :model max-requests :max-requests :or {max-requests 20}} (:judge definition)
        judge (or judge-fn (when judge-model (model-judge judge-model)))]
    (evaluation/make-evaluator
     {:id (verifier-id b) :version 1 :tier (or tier (dvergr.catalog.room/tier b))
      :basis (cond-> {:bundle (str id)} judge-model (assoc :judge judge-model))
      :capture (fn [{world :world/room}]
                 (cond-> {:files (into {} (map #(ws/read-tree world %)) dirs)}
                   (:fetched definition)
                   (assoc :fetched (bounded-pages (effects/fetched-pages (effects/world-recording (:ctx world)))))))
      :observe (fn [{:keys [default result] captured :execution/evidence}]
                 (let [input {:files (:files captured) :fetched (:fetched captured {})
                              :params params :gold gold}
                       requests (when judge (judge-requests checker input max-requests))]
                   (cond-> (assoc default :run-status (:run/status result) :files (:files captured)
                                  :fetched (:fetched captured {}))
                     requests (assoc :judgements (into {} (map (fn [{:keys [id prompt]}] [id (judge prompt)]))
                                                       requests)
                                     :judge-requests requests))))
      :verify (fn [_ {:keys [run-status files fetched judgements]}]
                (let [{:keys [checks reward]} (run-checker checker {:files files :fetched fetched
                                                                    :judgements (or judgements {})
                                                                    :params params :gold gold})]
                  {:checks (assoc checks :completed? (= :completed run-status))
                   :reward (if (= :completed run-status) reward 0.0)}))})))

(defn environment
  [{:keys [definition name] :as b} setup ev {:keys [timeout-ms params]}]
  (let [ref (evaluation/evaluator-ref ev)]
    (environment/make-environment
     {:id (keyword "room-workflow" name)
      :task (task b params)
      ;; the basis names the bundle: a changed checker is a different verifier
      :verifier {:id (:verifier/id ref) :version (:verifier/version ref) :basis (:verifier/basis ref)}
      :limits {:timeout-ms (or timeout-ms (:timeout-ms definition) (* 10 60 1000))
               :cancel-timeout-ms 30000 :on-timeout :verdict}
      :world (cond-> {:isolation :ctx :settlement :discard
                      :setup (evaluation/world-setup-ref setup)}
               ;; the checker is given what the attempt fetched: record it
               (:fetched definition) (assoc :effects {:record true}))
      :metadata {:bundle (str (:id b))}})))

(defn experiment-plan
  "What `dvergr.agent.experiment.runner` needs to benchmark `b` on `models`."
  [b {:keys [models budget-dollars timeout-ms prompt params tier judge-fn] :as opts}]
  (let [setup (world-setup b)
        ev (evaluator b {:params params :tier tier :judge-fn judge-fn})
        {:keys [team ids]} (workflow/candidates {:models models
                                                 :profile (or (get-in b [:definition :profile]) "developer")
                                                 :budget-dollars budget-dollars :prompt prompt})]
    {:benchmark :room-workflow
     :capabilities {:world-setup setup :evaluator ev}
     :environments [(environment b setup ev opts)]
     :team team
     :models (mapv #(:agent/model-policy (roster/agent team %)) ids)
     :dataset {:id (keyword "room-workflow" (:name b))
               :metadata {:bundle (str (:id b))}}}))

(defn experiment!
  "Benchmark bundle `b` in its own process (see `dvergr.catalog.wiki/experiment!`)."
  [b {:keys [dir repetitions parallelism] :or {repetitions 1} :as opts}]
  ((requiring-resolve 'dvergr.agent.experiment.runner/run!)
   (assoc (experiment-plan b opts) :dir dir :repetitions repetitions
          :parallelism (or parallelism 1))))

;; ---------------------------------------------------------------------------
;; Export and import: a bundle travels as its files and a manifest
;; ---------------------------------------------------------------------------

(def manifest-format "dvergr-workflow/1")

(defn- dvergr-version []
  (or (some-> (io/resource "META-INF/maven/org.replikativ/dvergr/pom.properties")
              slurp
              (->> (re-find #"(?m)^version=(.+)$"))
              second)
      "dev"))

(defn export
  "Bundle `name` of `room` as `{:manifest :files}`: the files by path relative
   to the bundle, and a manifest naming its content id, the dvergr version it
   was exported from and its calibration there. Trust does not travel: a
   receiving host promotes the bundle itself."
  [room name]
  (let [files (bundle-files room name)
        b (bundle name files)
        c (when (:calibration b) (calibrate b))]
    {:manifest {:format manifest-format
                :name name
                :bundle (str (:id b))
                :title (get-in b [:definition :title])
                :dvergr-version (dvergr-version)
                :exported-at (java.util.Date.)
                :calibration (if c (select-keys c [:ok? :problems]) {:ok? false :problems ["no calibration.edn"]})}
     :files (into (sorted-map) files)}))

(defn verify-export
  "The bundle an export describes, or throws: the files must be well formed and
   be exactly the bundle its manifest names."
  [{:keys [manifest files]}]
  (when-not (= manifest-format (:format manifest))
    (throw (ex-info (str "Not a workflow export (format " (pr-str (:format manifest)) ")")
                    {:type ::invalid-export})))
  (let [b (bundle (:name manifest) files)]
    (when-not (= (:bundle manifest) (str (:id b)))
      (throw (ex-info "The files are not the bundle the manifest names: changed after export"
                      {:type ::export-mismatch :manifest (:bundle manifest) :files (str (:id b))})))
    b))

(defn import!
  "Install an export in `room` as `workflows/<as>/` (default its own name),
   committed. Returns the bundle."
  [room {:keys [manifest files] :as export} & [as]]
  (let [b (verify-export export)
        name (or as (:name manifest))]
    (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
      (throw (ex-info (str "A workflow name is lowercase letters, digits and dashes: " name)
                      {:type ::invalid-name :name name})))
    (ws/ensure-workspace! room)
    (ws/seed! room (into {} (map (fn [[p t]] [(str "/workflows/" name "/" p) t])) files))
    (assoc b :name name)))

(defn read-dir
  "A bundle from a local directory (an unpacked export, or one being written)."
  [dir]
  (let [root (.getCanonicalFile (io/file dir))
        base (str (.getPath root) "/")
        files (into {} (for [f (file-seq root) :when (.isFile f)]
                         [(subs (.getPath (.getCanonicalFile f)) (count base)) (slurp f)]))]
    (bundle (.getName root) files)))
