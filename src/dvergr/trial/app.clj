(ns dvergr.trial.app
  "Trial app: A simple notes system to validate the living software model.

   This is Phase 1 - pure REPL, no UI.

   Architecture:
   - Spindel execution context for data isolation (YggRef to datahike)
   - SCI context with spindel integration for code isolation
   - Deep integration: spin/await work naturally in SCI
   - *execution-context* in SCI points to forked runtime (transparent)

   Usage:
     (require '[dvergr.trial.app :as app])
     (app/init!)

     ;; Use the app
     (app/create-note! \"My first note\" [:idea])
     (app/search! \"first\")

     ;; Trigger observation (creates agent environments)
     (def envs (app/observe!))

     ;; Run agent code in environment
     (app/run-in-env (first envs) \"(list-notes)\")

     ;; Merge or discard
     (app/approve! (first envs))
  "
  (:require [datahike.api :as dh]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.signal :as sig]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [yggdrasil.adapters.git :as git-adapter]
            [dvergr.agent.sci :as agent-sci]
            [dvergr.agent.config :as agent-core]
            [dvergr.sandbox :as sandbox]))

;; ============================================================================
;; Schema
;; ============================================================================

(def note-schema
  [{:db/ident :note/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :note/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :note/tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :note/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def proposal-schema
  [{:db/ident :proposal/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/rationale
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/agent
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/evidence
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :proposal/code-diff
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def full-schema (concat note-schema proposal-schema))

;; ============================================================================
;; State - All via spindel (fork-safe)
;; ============================================================================

(defonce ^{:doc "YggRef to datahike - resolves from current execution context"}
  ydb nil)

(defonce ^{:doc "YggRef to git repo - provides isolated worktrees per fork"}
  git-repo nil)

(defonce ^{:doc "Spindel signal for activity tracking - fork-safe"}
  activity nil)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- conn
  "Get datahike connection from current context's ydb."
  []
  (:conn @ydb))

(defn- db
  "Get current datahike db value."
  []
  @(conn))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize the trial app.

   Creates:
   - Spindel execution context (bound globally)
   - In-memory datahike registered as YggRef
   - Activity signal for tracking user actions"
  []
  (let [rt (ctx/create-execution-context)
        cfg {:store {:backend :memory
                     :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    ;; Create datahike
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      ;; Transact schema
      (dh/transact conn full-schema)

      ;; Bind execution context at entry point
      (alter-var-root #'rtc/*execution-context* (constantly rt))

      ;; Register datahike as YggRef - auto-forks with context
      (alter-var-root #'ydb
        (constantly (ygg/register! (dh-adapter/create conn {:system-name "app-db"}))))

      ;; Activity signal - fork-safe reactive state
      (alter-var-root #'activity
        (constantly (sig/signal {:searches []
                                 :creates []
                                 :updates []})))

      ;; Register git repo for file isolation - uses dvergr project
      ;; Worktrees will be created in .git-worktrees/ for branch isolation
      (let [repo-path (System/getProperty "user.dir")
            worktrees-dir (str repo-path "/.git-worktrees")]
        (alter-var-root #'git-repo
          (constantly (ygg/register! (git-adapter/create repo-path
                                                          {:system-name "dvergr-repo"
                                                           :worktrees-dir worktrees-dir})))))

      (println "Trial app initialized.")
      (println "")
      (println "Architecture:")
      (println "  @ydb      - YggRef to datahike (auto-forks with context)")
      (println "  @git-repo - YggRef to git (worktrees per fork)")
      (println "  @activity - Spindel signal (fork-safe)")
      (println "  SCI       - Deep integration with spindel (spin/await work)")
      (println "")
      (println "Usage:")
      (println "  (create-note! \"content\" [:tag1 :tag2])")
      (println "  (search! \"query\")")
      (println "  (list-notes)")
      (println "  (observe!)               ; create agent environments")
      (println "  (run-in-env env \"code\") ; run code in agent env")
      (println "  (approve! env)           ; merge agent's work")
      :ok)))

(defn stop!
  "Clean up - reset vars."
  []
  (alter-var-root #'ydb (constantly nil))
  (alter-var-root #'git-repo (constantly nil))
  (alter-var-root #'activity (constantly nil))
  (alter-var-root #'rtc/*execution-context* (constantly nil))
  (println "Trial app stopped."))

;; ============================================================================
;; Core Operations (exposed to agents via SCI)
;; ============================================================================

(defn create-note!
  "Create a new note. Updates activity signal."
  [content tags]
  (let [note {:note/id (random-uuid)
              :note/content content
              :note/tags (set tags)
              :note/created-at (java.util.Date.)}]
    (dh/transact (conn) [note])
    ;; Update activity signal (fork-safe)
    (swap! activity update :creates conj
           {:note-id (:note/id note)
            :content-preview (subs content 0 (min 50 (count content)))
            :tags tags
            :at (java.util.Date.)})
    (println (str "Created note: " (:note/id note)))
    note))

(defn search!
  "Search notes by content. Updates activity signal."
  [query]
  (let [results (dh/q '[:find [(pull ?e [*]) ...]
                        :in $ ?q
                        :where
                        [?e :note/content ?c]
                        [(clojure.string/includes? ?c ?q)]]
                      (db) query)]
    ;; Update activity signal
    (swap! activity update :searches conj
           {:query query
            :result-count (count results)
            :at (java.util.Date.)})
    (println (str "Found " (count results) " notes matching \"" query "\""))
    results))

(defn list-notes
  "List all notes."
  []
  (dh/q '[:find [(pull ?e [*]) ...]
          :where [?e :note/id _]]
        (db)))

(defn get-note
  "Get note by id."
  [id]
  (dh/pull (db) '[*] [:note/id id]))

(defn update-note!
  "Update a note's content or tags."
  [id updates]
  (let [tx-data (assoc updates :note/id id)]
    (dh/transact (conn) [tx-data])
    (swap! activity update :updates conj
           {:note-id id
            :updates (keys updates)
            :at (java.util.Date.)})
    (get-note id)))

(defn delete-note!
  "Delete a note by id."
  [id]
  (dh/transact (conn) [[:db/retractEntity [:note/id id]]])
  (println (str "Deleted note: " id))
  :deleted)

;; ============================================================================
;; Activity Analysis
;; ============================================================================

(defn activity-summary
  "Summarize the activity signal for analysis."
  []
  (let [{:keys [searches creates updates]} @activity]
    {:search-count (count searches)
     :create-count (count creates)
     :update-count (count updates)
     :search-to-create-ratio (if (pos? (count creates))
                               (/ (count searches) (count creates))
                               (count searches))
     :recent-searches (take-last 5 searches)
     :recent-creates (take-last 5 creates)}))

(defn detect-patterns
  "Analyze activity for patterns that might suggest features."
  []
  (let [{:keys [search-count create-count search-to-create-ratio]} (activity-summary)
        {:keys [searches]} @activity
        patterns []]
    (cond-> patterns
      ;; Pattern: High search, low create
      (and (> search-count 5) (> search-to-create-ratio 5))
      (conj {:pattern :high-search-low-create
             :evidence {:searches search-count
                        :creates create-count
                        :ratio search-to-create-ratio}
             :suggestion "Add save-from-search feature"})

      ;; Pattern: Repeated similar searches
      (let [freq (frequencies (map :query searches))
            repeated (filter #(> (val %) 2) freq)]
        (seq repeated))
      (conj {:pattern :repeated-searches
             :evidence {:repeated-queries
                        (->> (frequencies (map :query searches))
                             (filter #(> (val %) 2))
                             (into {}))}
             :suggestion "Add saved searches or filters"})

      ;; Pattern: Notes without tags
      (let [notes (list-notes)
            untagged (filter #(empty? (:note/tags %)) notes)]
        (> (count untagged) 3))
      (conj {:pattern :untagged-notes
             :evidence {:untagged-count
                        (count (filter #(empty? (:note/tags %)) (list-notes)))}
             :suggestion "Add auto-tagging or tag suggestions"}))))

;; ============================================================================
;; Feature Implementations (predefined for Phase 1)
;; ============================================================================

(def ^:private feature-implementations
  {:high-search-low-create
   {:title "Add save-from-search feature"
    :rationale "Users search frequently but rarely create notes. Allow saving search results directly as notes."
    :code-diff
    "(defn save-from-search!
  \"Save a search result as a new note.\"
  [result]
  (create-note!
    (:note/content result)
    (into #{:from-search} (:note/tags result))))"}

   :repeated-searches
   {:title "Add saved searches feature"
    :rationale "Users repeat the same searches. Allow saving frequent searches for quick access."
    :code-diff
    "(def saved-searches (atom #{}))

(defn save-search! [query]
  (swap! saved-searches conj query)
  (println (str \"Saved search: \" query)))

(defn run-saved-search! [query]
  (search! query))"}

   :untagged-notes
   {:title "Add auto-tagging suggestions"
    :rationale "Many notes lack tags. Suggest tags based on content keywords."
    :code-diff
    "(def common-tags
  {:clojure #{\"clojure\" \"clj\" \"repl\" \"macro\"}
   :database #{\"database\" \"db\" \"sql\" \"datahike\"}
   :web #{\"http\" \"api\" \"rest\" \"html\"}})

(defn suggest-tags [content]
  (let [words (set (clojure.string/split (clojure.string/lower-case content) #\"\\s+\"))]
    (for [[tag keywords] common-tags
          :when (seq (clojure.set/intersection words keywords))]
      tag)))"}})

;; ============================================================================
;; Agent Environment Creation (Deep Integration)
;; ============================================================================

(def ^:private observer-agent
  "Pre-configured observer agent for pattern detection."
  (agent-core/make-agent
    {:name "pattern-observer"
     :isolation :sci
     :permissions #{:use-tools}
     :tools #{:list-notes :create-note! :search! :get-note}}))

(defn create-agent-env
  "Create agent environment with deep spindel/SCI integration.

   The returned environment has:
   - :runtime    - Forked spindel execution context
   - :sci-ctx    - SCI context with *execution-context* bound to forked runtime
   - :agent      - Agent configuration

   Agent code in SCI automatically uses the forked context because
   *execution-context* is initialized to the forked runtime."
  ([]
   (create-agent-env observer-agent))
  ([agent]
   (let [;; Fork the current context - yggdrasil auto-forks @ydb
         forked-runtime (ctx/fork-context rtc/*execution-context*)

         ;; Create SCI context via agent-sci infrastructure
         ;; This sets *execution-context* in SCI to forked-runtime
         {:keys [runtime sci-ctx]}
         (agent-sci/create-agent-execution-context
           agent
           forked-runtime
           {:tools {'list-notes list-notes
                    'create-note! create-note!
                    'search! search!
                    'get-note get-note
                    'update-note! update-note!
                    'delete-note! delete-note!
                    'activity-summary activity-summary
                    'detect-patterns detect-patterns}})]

     {:runtime runtime
      :sci-ctx sci-ctx
      :agent agent
      :fork-id (:fork-id forked-runtime)})))

(defn run-in-env
  "Run code in agent environment.

   The SCI context already has *execution-context* bound to the forked runtime,
   so agent code naturally uses forked datahike, signals, etc.

   Example:
     (run-in-env env \"(list-notes)\")
     (run-in-env env \"(create-note! \\\"From agent\\\" [:auto])\")"
  [{:keys [sci-ctx]} code]
  (sandbox/eval-code sci-ctx code))

(defn run-spin-in-env
  "Run a spin in agent environment.

   Example:
     (run-spin-in-env env
       \"(spin
          (let [notes (await (list-notes))]
            (println \\\"Found\\\" (count notes) \\\"notes\\\")
            notes))\")"
  [{:keys [sci-ctx]} code]
  (sandbox/eval-code sci-ctx
    (str "(require '[org.replikativ.spindel.core :refer [spin]]
                  '[org.replikativ.spindel.core :refer [await]])\n"
         code)))

;; ============================================================================
;; Observer - Creates Agent Environments
;; ============================================================================

(defn observe!
  "Detect patterns and create agent environments for each.

   Returns a vector of environment handles. Each has:
   - :runtime   - Forked spindel context
   - :sci-ctx   - SCI with forked context bound
   - :pattern   - The detected pattern
   - :proposal  - Proposal data

   Use (run-in-env env code) to run agent code.
   Use (approve! env) or (reject! env) to merge/discard."
  []
  (let [patterns (detect-patterns)]
    (if (empty? patterns)
      (do (println "No patterns detected.")
          [])
      (do
        (println (str "Detected " (count patterns) " pattern(s):"))
        (doseq [p patterns]
          (println (str "  - " (:pattern p) ": " (:suggestion p))))
        (println "")
        (println "Creating agent environments...")
        (println "")

        (let [envs
              (for [pattern patterns
                    :let [impl (get feature-implementations (:pattern pattern))]
                    :when impl]
                (let [;; Create agent environment (forked context + SCI)
                      env (create-agent-env)
                      proposal-id (random-uuid)

                      proposal {:proposal/id proposal-id
                                :proposal/type :feature
                                :proposal/title (:title impl)
                                :proposal/rationale (:rationale impl)
                                :proposal/status :pending
                                :proposal/agent "pattern-observer"
                                :proposal/created-at (java.util.Date.)
                                :proposal/evidence (pr-str (:evidence pattern))
                                :proposal/code-diff (:code-diff impl)}]

                  ;; Transact proposal in the forked context
                  (binding [rtc/*execution-context* (:runtime env)]
                    (dh/transact (conn) [proposal]))

                  (println (str "  Created: " (:proposal/title proposal)))
                  (println (str "    Fork:  " (:fork-id env)))

                  ;; Return environment with proposal
                  (assoc env
                         :proposal proposal
                         :pattern (:pattern pattern))))]

          (println "")
          (println "Agent environments ready.")
          (println "")
          (println "Run code in environment:")
          (println "  (run-in-env env \"(list-notes)\")")
          (println "  (run-spin-in-env env \"(spin (await ...))\")")
          (println "")
          (println "Merge or discard:")
          (println "  (approve! env)")
          (println "  (reject! env)")
          (println "")
          (vec envs))))))

;; ============================================================================
;; Proposal Review
;; ============================================================================

(defn show-proposal
  "Pretty print an environment's proposal."
  [{:keys [runtime proposal pattern fork-id] :as env}]
  (if (nil? env)
    (println "No environment provided.")
    (do
      (println "")
      (println (apply str (repeat 60 "=")))
      (println (str "PROPOSAL: " (:proposal/title proposal)))
      (println (apply str (repeat 60 "=")))
      (println "")
      (println (str "ID:       " (:proposal/id proposal)))
      (println (str "Type:     " (:proposal/type proposal)))
      (println (str "Status:   " (:proposal/status proposal)))
      (println (str "Pattern:  " pattern))
      (println (str "Agent:    " (:proposal/agent proposal)))
      (println (str "Fork ID:  " fork-id))
      (println "")
      (println "RATIONALE:")
      (println (:proposal/rationale proposal))
      (println "")
      (println "EVIDENCE:")
      (println (:proposal/evidence proposal))
      (println "")
      (println "PROPOSED CODE:")
      (println (apply str (repeat 60 "-")))
      (println (:proposal/code-diff proposal))
      (println (apply str (repeat 60 "-")))
      (println "")
      (println "Test in environment:")
      (println "  (run-in-env env \"(list-notes)\")")
      (println ""))))

(defn list-envs
  "List environment summaries."
  [envs]
  (if (empty? envs)
    (println "No environments.")
    (do
      (println "")
      (println "=== Agent Environments ===")
      (println "")
      (doseq [[idx env] (map-indexed vector envs)]
        (println (str "  [" idx "] " (get-in env [:proposal :proposal/title])))
        (println (str "      Pattern: " (:pattern env)))
        (println (str "      Fork:    " (:fork-id env))))
      (println "")
      (println "Use (show-proposal (nth envs idx)) for details")
      (println ""))))

;; ============================================================================
;; Merge / Discard
;; ============================================================================

(defn approve!
  "Approve and merge an agent environment's work to parent.

   Merges all yggdrasil systems (datahike branches, etc.) from
   the forked context back to the parent."
  [{:keys [runtime proposal] :as env}]
  (if (nil? env)
    (println "No environment provided.")
    (do
      (println "")
      (println (apply str (repeat 60 "=")))
      (println "APPROVING")
      (println (apply str (repeat 60 "=")))
      (println "")
      (println (str "Title: " (:proposal/title proposal)))
      (println "")

      ;; Merge the forked context's yggdrasil systems to parent
      (ygg/merge-to-parent! runtime)

      (println "Merged to parent.")
      (println "")
      :approved)))

(defn reject!
  "Reject and discard an agent environment's work.

   Cleans up the forked context without merging."
  [{:keys [runtime proposal] :as env}]
  (if (nil? env)
    (println "No environment provided.")
    (do
      (println "")
      (println "Rejecting: " (:proposal/title proposal))

      ;; Discard the forked context
      (ygg/discard-from-parent! runtime)

      (println "Discarded.")
      (println "")
      :rejected)))

;; ============================================================================
;; Pretty Printing
;; ============================================================================

(defn print-notes
  "Pretty print notes."
  [notes]
  (if (empty? notes)
    (println "No notes.")
    (doseq [note notes]
      (println "---")
      (println (str "ID: " (:note/id note)))
      (println (str "Tags: " (pr-str (:note/tags note))))
      (println (str "Content: " (:note/content note)))))
  (println ""))

(defn print-activity
  "Pretty print activity summary."
  []
  (let [summary (activity-summary)]
    (println "=== Activity Summary ===")
    (println (str "Searches: " (:search-count summary)))
    (println (str "Creates:  " (:create-count summary)))
    (println (str "Updates:  " (:update-count summary)))
    (println (str "Search/Create Ratio: "
                  (if (number? (:search-to-create-ratio summary))
                    (format "%.1f" (float (:search-to-create-ratio summary)))
                    "N/A")))
    (println "")
    (when (seq (:recent-searches summary))
      (println "Recent searches:")
      (doseq [s (:recent-searches summary)]
        (println (str "  - \"" (:query s) "\" (" (:result-count s) " results)"))))
    (println "")))

;; ============================================================================
;; REPL Convenience
;; ============================================================================

(comment
  ;; ============================================================
  ;; FULL LIFECYCLE DEMO
  ;; ============================================================

  ;; 1. Initialize
  (init!)

  ;; 2. Use the app (create some notes)
  (create-note! "Learning Clojure basics" [:clojure :learning])
  (create-note! "Reactive programming with spindel" [:clojure :frp :spindel])
  (create-note! "Datahike branching experiments" [:clojure :datahike])

  ;; 3. Search a lot (to trigger patterns)
  (dotimes [_ 6] (search! "Clojure"))
  (search! "reactive")
  (search! "Clojure")
  (search! "spindel")

  ;; 4. Check activity
  @activity
  (print-activity)

  ;; 5. Run observer (creates agent environments)
  (def envs (observe!))

  ;; 6. List environments
  (list-envs envs)

  ;; 7. Inspect a proposal
  (show-proposal (first envs))

  ;; 8. Run code in agent environment (uses forked context!)
  (run-in-env (first envs) "(list-notes)")
  (run-in-env (first envs) "(create-note! \"Agent note\" [:auto])")
  (run-in-env (first envs) "(count (list-notes))")  ;; Should see agent's note

  ;; Parent doesn't see agent's note yet
  (count (list-notes))

  ;; 9. Run spins in environment
  (run-spin-in-env (first envs)
    "(spin
       (let [notes (list-notes)]
         {:count (count notes)
          :titles (map :note/content notes)}))")

  ;; 10. Approve or reject
  (approve! (first envs))
  ;; Now parent sees agent's note
  (count (list-notes))

  ;; Or reject
  ;; (reject! (first envs))

  ;; ============================================================
  ;; UNDERSTANDING DEEP INTEGRATION
  ;; ============================================================

  ;; Create standalone agent environment
  (def env (create-agent-env))

  ;; The SCI context has *execution-context* = forked runtime
  ;; So @ydb in SCI resolves to forked datahike branch

  ;; Agent creates note (in forked branch)
  (run-in-env env "(create-note! \"Test from SCI\" [:sci :test])")

  ;; Agent sees its note
  (run-in-env env "(count (list-notes))")

  ;; Parent doesn't see it
  (count (list-notes))

  ;; Merge brings it to parent
  (approve! env)
  (count (list-notes))

  ;; ============================================================
  ;; CLEANUP
  ;; ============================================================
  (stop!))
