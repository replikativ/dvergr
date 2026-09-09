(ns dvergr.benchmarks.rss
  "Offline repair of document-relative feed discovery in a pinned intake source.
   Fetch is explicitly stubbed; XML parsing uses the ordinary sandbox binding."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.benchmarks.coding-workspace :as workspace]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as sandbox-io]
            [dvergr.substrate.geschichte :as g]
            [hasch.core :as hasch]
            [muschel.fs :as fs]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as ec]))

(def source-path "/dvergr/intake/rss.clj")
(def test-path "/test/rss_repair_test.clj")
(def dependency-path "/dvergr/intake/core.clj")
(def original-source (slurp (io/resource "benchmarks/rss/rss.clj")))
(def dependency-source
  (str ";; Benchmark-only dependency stub, not the production intake implementation.\n"
       "(ns dvergr.intake.core)\n"
       "(defn fetch-text [& _] {:error \"Offline fixture: stub fetch-text with with-redefs\"})\n"))
(def url-cases (edn/read-string (slurp (io/resource "benchmarks/rss/url-cases.edn"))))

(defn- with-fetch [fetch-form call]
  (str "(with-redefs [dvergr.intake.core/fetch-text " fetch-form "] " call ")"))

(defn- with-body [body call]
  (with-fetch (str "(fn [& _] " (pr-str body) ")") call))

(def rss-body
  "<rss><channel><title>News</title><item><title>A</title><link>https://example.org/a</link><description><![CDATA[<b>Hello</b> world]]></description><pubDate>2026-01-01</pubDate><author>Ada</author><category>news</category></item><item><title>B</title></item></channel></rss>")
(def atom-body
  "<feed xmlns='http://www.w3.org/2005/Atom'><title>Updates</title><entry><title>A</title><link href='https://example.org/a' rel='alternate'/><summary>Hello</summary><updated>2026-01-01</updated><category term='news'/></entry></feed>")

(def cases
  (into
   (mapv (fn [i [url href expected]]
           {:id (keyword (str "url-" i))
            :form (with-body (str "<link rel='alternate' type='application/rss+xml' href='"
                                  href "' title='Feed'>")
                    (str "(dvergr.intake.rss/discover-feeds " (pr-str url) ")"))
            :expected [{:url expected :title "Feed" :type "application/rss+xml"}]})
         (range) url-cases)
   [{:id :fetch-error
     :form (with-body {:error "not-found"} "(dvergr.intake.rss/discover-feeds \"https://example.org\")")
     :expected {:error "not-found"}}
    {:id :fallback
     :form (with-fetch
             "(fn [url & _] (get {\"https://example.org/page\" \"<html/>\", \"https://example.org/feed\" \"feed\"} url {:error \"missing\"}))"
             "(vec (dvergr.intake.rss/discover-feeds \"https://example.org/page\"))")
     :expected [{:url "https://example.org/feed" :title "/feed" :type "probe"}]}
    {:id :rss-parse-and-limit
     :form (with-body rss-body "(dvergr.intake.rss/fetch-feed \"https://example.org/feed\" :count 1)")
     :expected {:feed-title "News" :items [{:title "A" :url "https://example.org/a"
                                            :summary "Hello world" :date "2026-01-01"
                                            :author "Ada" :tags ["news"]}]}}
    {:id :atom-parse
     ;; The snapshot has an unrelated empty-string fallback bug for Atom
     ;; summary/date fields. Do not demand that a URL-only repair fix or freeze
     ;; it; check the working title/link/category surface independently.
     :form (with-body atom-body
             "(update (dvergr.intake.rss/fetch-feed \"https://example.org/atom\") :items (fn [items] (mapv #(select-keys % [:title :url :tags]) items)))")
     :expected {:feed-title "Updates" :items [{:title "A" :url "https://example.org/a"
                                               :tags ["news"]}]}}]))

(def check-expression
  ;; Never require the candidate namespace here: missing saved definitions
  ;; must not be silently supplied from a host classpath or another workspace.
  (str "{" (str/join " " (map #(str (pr-str (:id %)) " " (:form %)) cases)) "}"))

(def task
  (str "Repair document-relative feed URL resolution in " source-path ". "
       "discover-feeds currently resolves links against the site root rather than the page URL. "
       "Support child paths, ../, ./, root-relative and scheme-relative references; preserve "
       "absolute HTTP(S) references, queries, fragments, encoded paths and ports. Preserve the "
       "public result maps, fallback probing, and RSS/Atom parsing. Do not rewrite the HTML parser. "
       "Use clojure_eval to inspect/edit the saved SOURCE and require dvergr.intake.rss :reload. "
       "Write/run clojure.test regressions at " test-path "; load tests using load-string/slurp "
       "rather than reloading clojure.test. The fixture replaces dvergr.intake.core/fetch-text "
       "with an explicit offline stub: use with-redefs to supply HTML/XML bodies or {:error ...} "
       "for each test. Do not change the stub to fix the task; verification reconstructs it. "
       "A fresh SCI interpreter verifies saved RSS source with 18 public URL cases and "
       "error/fallback/RSS/Atom regressions, not your final prose or REPL definitions. "
       "Finish with your counterexample, change summary and test results."))

(def manifest
  {:fixture/version 1 :source/repository "https://github.com/replikativ/dvergr-sandbox"
   :source/commit "25436365b401b928fc8bebf295afe017cd9c9a45"
   :source/path "dvergr/intake/rss.clj" :source/hash (hasch/uuid original-source)
   :dependency/hash (hasch/uuid dependency-source)
   :runtime/profile :dvergr-sci-closed-uri-v1
   :checks/version 1 :checks/hash (hasch/uuid [cases check-expression])
   :capture/paths [source-path test-path] :capture/max-file-bytes 32768
   :verification/timeout-ms 3000})
(def basis (hasch/uuid [manifest task]))
(def setup-ref {:setup/id :coding/rss :setup/version 1 :setup/basis basis})
(def verifier-ref {:verifier/id :coding/rss :verifier/version 1 :verifier/basis basis})

(defn- offline! []
  (sandbox-io/install-http-fixture!
   {:id (hasch/uuid [:coding/rss-offline-v1]) :env {}
    :transport (fn [_] {:status 404 :headers {} :body "Offline RSS fixture"})}))

(defn definition []
  (environment/make-environment
   {:id :coding/rss :task task :verifier {:id :coding/rss :version 1 :basis basis}
    :world {:isolation :ctx :settlement :discard :setup setup-ref}
    :limits {:timeout-ms 180000 :cancel-timeout-ms 10000}
    :metadata manifest}))

(defn world-setup []
  (evaluation/make-world-setup
   {:id :coding/rss :version 1 :basis basis
    :prepare (fn [{:keys [room]}]
               (binding [ec/*execution-context* (:ctx room)]
                 (offline!)
                 (let [filesystem (g/filesystem)]
                   (when-not filesystem
                     (throw (ex-info "RSS fixture needs a registered Geschichte workspace" {})))
                   (doseq [[path text] [[source-path original-source]
                                        [dependency-path dependency-source]
                                        [test-path ";; Write RSS regressions here.\n"]]]
                     (when-not (fs/write-string! filesystem path text false)
                       (throw (ex-info "Cannot seed RSS fixture" {:path path}))))))
               {:fixture/basis basis})}))

(defn check-source [source]
  (if-not (and (string? source) (<= 1 (alength (.getBytes ^String source "UTF-8")) 32768))
    {:source? false}
    (let [runtime (ctx/create-execution-context)]
      (try
        (binding [ec/*execution-context* runtime] (offline!))
        (let [interpreter (sandbox/fork-for-session runtime)
              _ (sandbox/setup-agent-namespaces! interpreter runtime)
              result (sandbox/eval-code
                      interpreter
                      (str "(load-string " (pr-str dependency-source) ") "
                           "(load-string " (pr-str source) ") " check-expression)
                      :execution-context runtime :timeout-ms 3000)]
          (into {:evaluated? (true? (:success result))}
                (map (fn [{:keys [id expected]}]
                       [id (and (true? (:success result)) (= expected (get (:value result) id)))]))
                cases))
        (finally (ctx/close-context! runtime))))))

(defn evaluator []
  (evaluation/make-evaluator
   {:id :coding/rss :version 1 :basis basis
    :capture (fn [{:keys [world/room]}]
               (workspace/capture room [source-path test-path] 32768))
    :observe (fn [{:keys [default execution/evidence result]}]
               (assoc default :artifacts evidence :fixture/basis basis
                      :completed? (= :completed (:run/status result))))
    :verify (fn [_ evidence]
              (let [file (get-in evidence [:artifacts :files source-path])
                    checks (assoc (check-source (when (= :ok (:status file)) (:source file)))
                                  :completed? (true? (:completed? evidence)))]
                {:checks checks :reward (if (every? true? (vals checks)) 1.0 0.0)}))}))
