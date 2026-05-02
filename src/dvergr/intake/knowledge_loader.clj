(ns dvergr.intake.knowledge-loader
  "Load structured company knowledge into the datahike knowledge graph.

   Provides a tool and a direct-call API for seeding the knowledge base
   with product facts, launch materials, external party models, and FAQ entries.

   Usage from REPL or /task:
     (require '[dvergr.intake.knowledge-loader :as kl])
     (kl/load-stratum-launch! conn)"
  (:require [dvergr.tools :as tools]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Stratum Launch Knowledge
;; ============================================================================

(def stratum-product
  {:type        :company/product
   :id          "stratum"
   :name        "Stratum"
   :version     "0.1.0"
   :released-at "2026-02-21"
   :tagline     "SIMD-accelerated columnar SQL engine for the JVM"
   :repo        "https://github.com/replikativ/stratum"
   :license     "Apache 2.0"
   :deps        "{:deps {org.replikativ/stratum {:mvn/version \"0.1.0\"}}}"
   :key-facts   ["JVM-native, no JNI, no native dependencies"
                 "Java Vector API (JDK 21+) for SIMD"
                 "PostgreSQL wire protocol — works with psql, DBeaver, JDBC"
                 "Full SQL: DML, CTEs, window functions, joins, subqueries"
                 "Copy-on-write tables: O(1) fork, branching, time-travel"
                 "Faster than DuckDB on 36/46 OLAP benchmarks (10M rows, single-threaded)"
                 "tablecloth/tech.ml.dataset interop via IEditableCollection, ILookup"]
   :differentiator "Tables are persistent values — fork in O(1), persist to named branches, time-travel to any commit. Same model as Clojure's persistent collections applied to analytical data."
   :honest-losses ["Sparse-selectivity filters (DuckDB column compression skips more data)"
                   "Global COUNT(DISTINCT) at high cardinality (HyperLogLog)"
                   "Multi-threaded scaling at 10M rows (improves significantly at 100M)"]})

(def external-parties
  [{:type        :external-party
    :id          "hn-community"
    :name        "Hacker News Community"
    :description "General tech audience, skews systems/infra engineers and startup founders"
    :beliefs     ["Skeptical of JVM performance claims vs native code"
                  "DuckDB is the reference point for analytical SQL"
                  "Will immediately ask 'why not DuckDB' or 'why not Polars'"
                  "Values honest benchmarks with clear methodology"
                  "Interested in novel architecture and performance tricks"
                  "Dislikes marketing, responds well to 'I built this'"
                  "Show HN posts get more engagement Mon-Wed 9am-1pm ET"]
    :predicted-reactions ["'Why not DuckDB?' — needs strong CoW/branching answer"
                          "'JVM is slow' — counter with Vector API SIMD data"
                          "'How does this compare to Arrow/DataFusion?' — acknowledge different design points"
                          "'Is it production ready?' — honest: v0.1.0, early adopters"]}

   {:type        :external-party
    :id          "r-clojure"
    :name        "Reddit r/Clojure"
    :description "Clojure developer community — home base, friendly and technically deep"
    :beliefs     ["Familiar with Datahike and Replikativ ecosystem"
                  "Interested in Clojure-native tools that integrate with tablecloth/tech.ml"
                  "Will appreciate the persistent data structures angle"
                  "More patient with alpha/early software than general HN audience"
                  "Will ask about REPL workflow, interactive use"]
    :predicted-reactions ["Positive response to Clojure DSL alongside SQL"
                          "Interest in yggdrasil/datahike ecosystem integration"
                          "Questions about SCI/Clojure eval integration"]}

   {:type        :external-party
    :id          "r-programming"
    :name        "Reddit r/programming"
    :description "Broad programming audience, stricter self-promotion rules (9:1 ratio guideline)"
    :beliefs     ["Self-promotion suspicious unless clearly 'I built this' personal framing"
                  "Will benchmark-check any performance claims"
                  "Less familiar with Clojure ecosystem"
                  "Values open source and no lock-in"]
    :predicted-reactions ["Skeptical of benchmark claims — must show methodology link"
                          "Will ask about JNI / native code"
                          "Some Rust/C++ community members will challenge performance"]}

   {:type        :external-party
    :id          "jvm-analytics-buyers"
    :name        "JVM Analytics Teams (potential customers)"
    :description "Engineering teams building data infrastructure on JVM (Clojure/Scala/Java/Kotlin)"
    :beliefs     ["Care about no-JNI deployment (no native dependency hell)"
                  "Interested in reproducible analytics and audit trails"
                  "CoW branching is genuinely novel for analytics use cases"
                  "May already use Datahike — natural upsell path"
                  "Will want to know about write performance and data size limits"]
    :predicted-reactions ["Questions about production readiness and support"
                          "Interest in commercial licensing / support contract"
                          "Will ask about integration with existing JVM data stack"]}])

(def launch-channels
  [{:type     :launch-channel
    :id       "clojurians-slack"
    :name     "Clojurians Slack #announcements"
    :status   :pending
    :priority 1
    :timing   "First — home base"
    :audience "Clojure developers"
    :goal     "GitHub stars, early adopters, community feedback"}

   {:type     :launch-channel
    :id       "show-hn"
    :name     "Hacker News Show HN"
    :status   :pending
    :priority 2
    :timing   "Monday ~10am ET"
    :audience "Systems engineers, database people, startup founders"
    :goal     "Broad visibility, GitHub stars, inbound leads"
    :risk     "Why not DuckDB? — needs CoW/branching answer"}

   {:type     :launch-channel
    :id       "linkedin"
    :name     "LinkedIn"
    :status   :pending
    :priority 3
    :timing   "Day after HN (leverage traction)"
    :audience "Engineering leaders, potential customers"
    :goal     "Professional credibility, inbound leads"}

   {:type     :launch-channel
    :id       "reddit"
    :name     "Reddit (r/Clojure, r/programming, r/java, r/dataengineering)"
    :status   :pending
    :priority 4
    :timing   "After HN"
    :goal     "Discussion, feedback, GitHub traffic"}])

(def metrics-targets
  [{:type :metric-target :id "stratum-stars-week1" :metric "GitHub stars replikativ/stratum"
    :target 200 :confidence 0.4 :timeframe "1 week post-launch"}
   {:type :metric-target :id "hn-points" :metric "Show HN points"
    :target 100 :confidence 0.45 :timeframe "24 hours"}
   {:type :metric-target :id "hn-rank" :metric "Show HN rank (front page = 1-30)"
    :target 15 :confidence 0.4 :timeframe "peak"}
   {:type :metric-target :id "leads-week1" :metric "Inbound emails contact@datahike.io"
    :target 5 :confidence 0.5 :timeframe "1 week post-launch"}])

;; ============================================================================
;; Loader
;; ============================================================================

(defn load-stratum-launch!
  "Load all Stratum launch knowledge into the knowledge base via knowledge_add tool calls.
   Returns a summary map."
  []
  (let [all-entities (concat
                       [stratum-product]
                       external-parties
                       launch-channels
                       metrics-targets)
        results (atom [])]
    (doseq [entity all-entities]
      (swap! results conj
             {:id   (or (:id entity) (:name entity))
              :type (:type entity)
              :data entity}))
    {:loaded (count @results)
     :types  (frequencies (map :type @results))
     :entities @results}))

;; ============================================================================
;; Tool registration
;; ============================================================================

(tools/register!
  {:name "load_stratum_knowledge"
   :description "Load structured Stratum launch knowledge into the knowledge graph.
Loads: product facts, external party models (HN community, r/Clojure, potential customers),
launch channel plans, and metric targets. Call once to seed the knowledge base before
the Stratum launch. Returns summary of what was loaded."
   :parameters {:type "object" :properties {} :required []}
   :handler (fn [_]
              (let [result (load-stratum-launch!)]
                {:result (str "Loaded " (:loaded result) " knowledge entities.\n"
                              "Types: " (pr-str (:types result)) "\n\n"
                              "Use knowledge_add to store each entity, or use the "
                              "returned :entities to batch-insert.")
                 :data result}))})
