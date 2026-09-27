(ns dvergr.catalog.room
  "Workflows defined in a room (doc/room-workflows.md): a bundle of files in
   the room's repository, `workflows/<name>/`, that the catalog runs like its
   own workflows.

     workflow.edn   {:title :doc :task \"… {param} …\" :params {…} :profile
                     :capture [\"/out\"] :timeout-ms}
     checker.clj    a namespace defining (check {:files :params :gold})
                    → {:checks {k bool} :reward 0..1}
     gold.edn       optional: the reference facts the checker scores against
     fixtures/…     the files each attempt's world starts with (at the root:
                    fixtures/docs/a.md → /docs/a.md)
     calibration.edn optional: {:reference {path text} :damaged {name {:files
                    {path text} :loses [check …]}}}, how the checker earns
                    trust (`calibrate`, `promote!`)

   The checker runs in SCI with no effects: it is given the files the attempt
   left under the captured directories and returns a verdict. It cannot read
   the room, the network or its own workspace. An unpromoted bundle's
   verifier is `:ad-hoc` on every receipt."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.workflow :as workflow]
            [dvergr.catalog.workspace :as ws]
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
      (conj "fixtures/ is empty: every attempt's world would start with nothing"))))

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

(defn run-checker
  "Run `source`'s `check` on `input`, bounded in time; the verdict, validated."
  [source input]
  (let [ctx (checker-ctx)
        run (future
              (let [{:keys [ns]} (sci/eval-string+ ctx source)
                    check (sci/eval-string* ctx (str "(ns-resolve '" ns " 'check)"))]
                (when-not check
                  (throw (ex-info "checker.clj defines no `check`" {:type ::no-check})))
                (@check input)))
        verdict (deref run checker-timeout-ms ::timeout)]
    (when (= ::timeout verdict)
      (future-cancel run)
      (throw (ex-info (str "The checker did not finish within " checker-timeout-ms "ms")
                      {:type ::checker-timeout})))
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
        run #(run-checker checker {:files % :gold gold :params params})]
    (if-not (:reference calibration)
      {:ok? false :bundle (str id) :problems ["calibration.edn has no :reference answer"]}
      (let [reference (run (:reference calibration))
            damaged (into (sorted-map)
                          (for [[k {:keys [files loses]}] (:damaged calibration)
                                :let [v (run files)
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
   own, committed). Its evidence is their digest."
  [{:keys [fixtures id]}]
  (let [digest (str (hasch/uuid fixtures))]
    (evaluation/make-world-setup
     {:id :room-workflow/fixtures :version 1 :basis {:bundle (str id) :fixtures digest}
      :prepare (fn [{world :room}]
                 (ws/ensure-workspace! world)
                 (ws/seed! world fixtures)
                 {:fixtures digest :files (count fixtures)})})))

(defn evaluator
  "The bundle's checker as an Evaluator: it captures the files under the
   bundle's `:capture` directories and runs the checker on them. `tier` is
   `:ad-hoc` unless the bundle was promoted."
  [{:keys [definition checker gold id] :as b} {:keys [params tier]}]
  (let [dirs (or (:capture definition) ["/out"])
        params (merge (:params definition) params)]
    (evaluation/make-evaluator
     {:id (verifier-id b) :version 1 :tier (or tier (dvergr.catalog.room/tier b))
      :basis {:bundle (str id)}
      :capture (fn [{world :world/room}]
                 {:files (into {} (map #(ws/read-tree world %)) dirs)})
      :observe (fn [{:keys [default result] captured :execution/evidence}]
                 (assoc default :run-status (:run/status result) :files (:files captured)))
      :verify (fn [_ {:keys [run-status files]}]
                (let [{:keys [checks reward]} (run-checker checker {:files files :params params :gold gold})]
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
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref setup)}
      :metadata {:bundle (str (:id b))}})))

(defn experiment-plan
  "What `dvergr.agent.experiment.runner` needs to benchmark `b` on `models`."
  [b {:keys [models budget-dollars timeout-ms prompt params tier] :as opts}]
  (let [setup (world-setup b)
        ev (evaluator b {:params params :tier tier})
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
