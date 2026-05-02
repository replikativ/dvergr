(ns dvergr.trial.simulation
  "Reproducible user simulations and LLM-driven observer for living software.

   User Simulations:
   - Deterministic workflows based on seed
   - Different personas (researcher, brain-dumper, organizer, forgetful)
   - Generate activity patterns for observer to analyze

   LLM Observer:
   - Analyzes activity signals
   - Proposes features in natural language
   - Writes code that runs in SCI sandbox
   - All changes isolated in forked context until approved"
  (:require [dvergr.trial.app :as app]
            [dvergr.agent.config :as agent-core]
            [dvergr.agent.sci :as agent-sci]
            [dvergr.model.chat :as model-chat]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [clojure.string :as str]))

;; ============================================================================
;; Reproducible Random Generator
;; ============================================================================

(defn make-rng
  "Create a reproducible random number generator from seed."
  [seed]
  (java.util.Random. seed))

(defn rand-int*
  "Random int in [0, n) using provided RNG."
  [^java.util.Random rng n]
  (.nextInt rng n))

(defn rand-nth*
  "Random element from coll using provided RNG."
  [^java.util.Random rng coll]
  (nth (vec coll) (rand-int* rng (count coll))))

(defn rand-double*
  "Random double in [0, 1) using provided RNG."
  [^java.util.Random rng]
  (.nextDouble rng))

(defn shuffle*
  "Shuffle collection using provided RNG."
  [^java.util.Random rng coll]
  (let [arr (java.util.ArrayList. coll)]
    (java.util.Collections/shuffle arr rng)
    (vec arr)))

;; ============================================================================
;; Content Generators (Deterministic)
;; ============================================================================

(def ^:private topics
  {:clojure ["Clojure macros are powerful for DSLs"
             "REPL-driven development speeds up iteration"
             "Persistent data structures enable safe concurrency"
             "Spec validates data at runtime boundaries"
             "Transducers compose without intermediate collections"
             "Core.async provides CSP-style concurrency"
             "Protocols enable polymorphism without inheritance"]

   :databases ["Datahike combines Datalog with persistence"
               "Datomic's immutability enables time-travel queries"
               "EAVT index optimizes entity lookups"
               "Datalog rules express recursive queries elegantly"
               "Schema-on-read vs schema-on-write tradeoffs"
               "Transaction functions ensure atomicity"]

   :web ["Ring middleware composes HTTP handling"
         "Hiccup generates HTML from data structures"
         "Reagent wraps React with ClojureScript"
         "Transit encodes rich data over HTTP"
         "WebSockets enable real-time updates"]

   :ideas ["What if notes could suggest related content?"
           "Auto-tagging based on content analysis"
           "Search history could predict next queries"
           "Frequently accessed notes deserve shortcuts"
           "Orphan notes might need attention"]})

(def ^:private all-topics (keys topics))

(defn- generate-note-content
  "Generate deterministic note content based on RNG and topic."
  [rng topic]
  (rand-nth* rng (get topics topic (get topics :ideas))))

(defn- generate-search-query
  "Generate deterministic search query based on RNG."
  [rng]
  (rand-nth* rng ["Clojure" "database" "REPL" "query" "macro"
                  "data" "async" "web" "note" "search"]))

;; ============================================================================
;; User Personas (Deterministic Simulations)
;; ============================================================================

(defn simulate-researcher
  "Researcher persona: topic-focused, high search-to-create ratio.

   Behavior:
   - Focuses on 1-2 topics
   - Searches frequently before creating
   - Creates detailed notes
   - Searches for things they just created

   Args:
     seed       - Random seed for reproducibility
     num-cycles - Number of research cycles"
  [seed num-cycles]
  (let [rng (make-rng seed)
        focus-topics (take 2 (shuffle* rng all-topics))]
    (println (str "Simulating researcher (seed=" seed ") focusing on: " focus-topics))
    (dotimes [cycle num-cycles]
      (let [topic (rand-nth* rng focus-topics)
            ;; Search 2-4 times before creating
            search-count (+ 2 (rand-int* rng 3))]
        ;; Search phase
        (dotimes [_ search-count]
          (app/search! (generate-search-query rng)))
        ;; Occasional create
        (when (< (rand-double* rng) 0.4)
          (app/create-note!
            (generate-note-content rng topic)
            [topic]))
        ;; Sometimes search for what we just learned
        (when (< (rand-double* rng) 0.3)
          (app/search! (name topic)))))
    (println "Researcher simulation complete.")))

(defn simulate-brain-dump
  "Brain-dumper persona: rapid creation, no organization.

   Behavior:
   - Creates many notes quickly
   - Rarely tags or searches
   - Short, unstructured content

   Args:
     seed      - Random seed for reproducibility
     num-notes - Number of notes to create"
  [seed num-notes]
  (let [rng (make-rng seed)]
    (println (str "Simulating brain-dump (seed=" seed ") creating " num-notes " notes"))
    (dotimes [i num-notes]
      (let [topic (rand-nth* rng all-topics)
            content (generate-note-content rng topic)
            ;; Usually no tags
            tags (if (< (rand-double* rng) 0.2)
                   [topic]
                   [])]
        (app/create-note! content tags))
      ;; Rare search
      (when (< (rand-double* rng) 0.1)
        (app/search! (generate-search-query rng))))
    (println "Brain-dump simulation complete.")))

(defn simulate-organizer
  "Organizer persona: structured, tag-focused workflow.

   Behavior:
   - Creates with tags
   - Searches by tags
   - Updates notes to add tags

   Args:
     seed       - Random seed for reproducibility
     num-cycles - Number of organize cycles"
  [seed num-cycles]
  (let [rng (make-rng seed)]
    (println (str "Simulating organizer (seed=" seed ")"))
    (dotimes [_ num-cycles]
      (let [topic (rand-nth* rng all-topics)]
        ;; Create with tags
        (when (< (rand-double* rng) 0.5)
          (app/create-note!
            (generate-note-content rng topic)
            [topic :organized]))
        ;; Search by topic
        (app/search! (name topic))
        ;; Search again
        (when (< (rand-double* rng) 0.3)
          (app/search! (name topic)))))
    (println "Organizer simulation complete.")))

(defn simulate-forgetful
  "Forgetful persona: repeats searches, can't find things.

   Behavior:
   - Searches same things repeatedly
   - Creates occasional notes
   - Searches for things that don't exist

   Args:
     seed       - Random seed for reproducibility
     num-cycles - Number of cycles"
  [seed num-cycles]
  (let [rng (make-rng seed)
        ;; This user has a few queries they keep repeating
        favorite-queries (take 3 (shuffle* rng ["Clojure" "REPL" "database" "notes" "todo"]))]
    (println (str "Simulating forgetful user (seed=" seed ") favorite queries: " favorite-queries))
    (dotimes [_ num-cycles]
      ;; Usually search for favorites
      (if (< (rand-double* rng) 0.7)
        (app/search! (rand-nth* rng favorite-queries))
        (app/search! (generate-search-query rng)))
      ;; Occasionally create
      (when (< (rand-double* rng) 0.15)
        (app/create-note!
          (generate-note-content rng (rand-nth* rng all-topics))
          [])))
    (println "Forgetful user simulation complete.")))

(defn simulate-mixed
  "Run a mixed simulation with multiple personas.

   Args:
     seed - Random seed for reproducibility"
  [seed]
  (println (str "\n=== Mixed Simulation (seed=" seed ") ===\n"))
  (simulate-researcher seed 5)
  (simulate-brain-dump (+ seed 1) 4)
  (simulate-forgetful (+ seed 2) 8)
  (println "\n=== Mixed Simulation Complete ===")
  (println "\nActivity Summary:")
  (let [summary (app/activity-summary)]
    (println (str "  Searches: " (:search-count summary)))
    (println (str "  Creates:  " (:create-count summary)))
    (println (str "  Ratio:    " (format "%.1f" (float (:search-to-create-ratio summary)))))))

;; ============================================================================
;; LLM-Driven Observer
;; ============================================================================

(def ^:private observer-system-prompt
  "You are an observer agent in a 'living software' system. You analyze user activity and propose improvements.

AVAILABLE TOOLS (in the sandbox):
- (list-notes) - returns all notes as maps with :note/content :note/tags :note/id
- (search! \"query\") - search notes by content, returns matches
- (create-note! \"content\" [:tag1 :tag2]) - create a note with tags
- (get-note id) - get note by UUID
- (activity-summary) - returns {:search-count N :create-count N :search-to-create-ratio N}

YOUR TASK:
1. Analyze the activity data provided
2. Identify ONE concrete improvement opportunity
3. Write ACTUAL WORKING Clojure code that implements it

RESPONSE FORMAT (MUST be valid EDN, NO markdown code blocks):
{:analysis \"2-3 sentences describing what patterns you observed\"
 :opportunity \"1 sentence describing the improvement opportunity\"
 :proposal {:title \"Short feature name\"
            :rationale \"Why this helps users\"
            :code \"(defn feature-name []
  (let [notes (list-notes)]
    ;; Your actual implementation
    ...))\"}}

CRITICAL REQUIREMENTS:
- The :code field MUST contain actual executable Clojure code (defn or do block)
- NOT a description of what the code should do
- Use ONLY the tools listed above
- The code will be evaluated in a Clojure sandbox
- Keep it simple - 5-15 lines of working code

EXAMPLE of correct :code value:
\"(defn suggest-tags []
  (let [notes (list-notes)
        untagged (filter #(empty? (:note/tags %)) notes)]
    (doseq [n untagged]
      (println (str \\\"Untagged: \\\" (:note/content n))))))\"")

(defn- build-observer-prompt
  "Build the prompt for the LLM observer with current activity context."
  []
  (let [summary (app/activity-summary)
        notes (app/list-notes)
        {:keys [searches]} @app/activity]
    (str "## Current Activity Data\n\n"
         "### Statistics\n"
         "- Total searches: " (:search-count summary) "\n"
         "- Total creates: " (:create-count summary) "\n"
         "- Search-to-create ratio: " (format "%.1f" (float (:search-to-create-ratio summary))) "\n"
         "- Total notes: " (count notes) "\n"
         "\n"
         "### Recent Searches (last 10)\n"
         (str/join "\n" (map #(str "- \"" (:query %) "\" (" (:result-count %) " results)")
                             (take-last 10 searches)))
         "\n\n"
         "### Search Query Frequency\n"
         (let [freq (->> searches
                         (map :query)
                         frequencies
                         (sort-by val >)
                         (take 5))]
           (str/join "\n" (map #(str "- \"" (first %) "\": " (second %) " times") freq)))
         "\n\n"
         "### Notes Overview\n"
         "- Notes with tags: " (count (filter #(seq (:note/tags %)) notes)) "\n"
         "- Notes without tags: " (count (filter #(empty? (:note/tags %)) notes)) "\n"
         "- Tag distribution: " (frequencies (mapcat :note/tags notes)) "\n"
         "\n"
         "### Sample Note Contents (first 5)\n"
         (str/join "\n" (map #(str "- " (subs (:note/content %) 0 (min 60 (count (:note/content %)))) "...")
                             (take 5 notes)))
         "\n\n"
         "Based on this activity data, analyze patterns and propose ONE concrete improvement.\n"
         "Respond with valid EDN as specified in your system prompt.")))

(defn- extract-text-from-response
  "Extract text content from provider response.
   Works with new model abstraction response format."
  [result]
  ;; New format just has :content directly
  (or (:content result) ""))

(defn observe-with-llm!
  "Run the LLM observer to analyze activity and propose improvements.

   Creates a forked context, calls the LLM with activity data,
   and returns an environment with the proposal.

   The LLM's proposed code will be available to run in the sandbox.

   Options:
     :model - LLM model (default: kimi-k2p5)
     :debug - Print debug info (default: false)

   Returns environment map with:
     :runtime  - Forked spindel context
     :sci-ctx  - SCI sandbox
     :proposal - LLM's proposal (parsed EDN)
     :raw-response - Raw LLM response"
  [& {:keys [model debug]
      :or {model "accounts/fireworks/models/kimi-k2p5"
           debug false}}]
  (println "\n=== LLM Observer ===\n")
  (println "Analyzing activity patterns...")

  (let [;; Build prompt
        prompt (build-observer-prompt)
        _ (when debug
            (println "\n--- Prompt ---")
            (println prompt)
            (println "--- End Prompt ---\n"))

        ;; Call LLM using model abstraction
        ;; Use higher token limit for thinking models
        _ (println "Calling LLM...")
        result (model-chat/chat
                 [{:role "system" :content observer-system-prompt}
                  {:role "user" :content prompt}]
                 {:model model
                  :max-tokens 4000})

        response (extract-text-from-response result)

        _ (when debug
            (println "\n--- Raw Response ---")
            (println response)
            (println "--- End Response ---\n"))

        ;; Parse EDN from response
        proposal (try
                   (let [;; Extract EDN block if wrapped in markdown
                         edn-str (-> response
                                     (str/replace #"```edn\s*" "")
                                     (str/replace #"```clojure\s*" "")
                                     (str/replace #"```\s*" "")
                                     str/trim)
                         parsed (read-string edn-str)
                         ;; Extract code - check multiple locations
                         code-raw (or (get-in parsed [:proposal :code])
                                      (:code parsed)
                                      (:implementation parsed))
                         ;; Check if it looks like actual code (starts with paren)
                         code (if (and code-raw
                                       (string? code-raw)
                                       (str/starts-with? (str/trim code-raw) "("))
                                code-raw
                                ";; No executable code provided")]
                     ;; Normalize field names (handle LLM variations)
                     {:analysis (or (:analysis parsed)
                                    (:description parsed)
                                    (:improvement parsed)
                                    "No analysis provided")
                      :opportunity (or (:opportunity parsed)
                                       (:rationale parsed)
                                       "No opportunity identified")
                      :proposal {:title (or (get-in parsed [:proposal :title])
                                            (:title parsed)
                                            (:improvement parsed)
                                            "Untitled")
                                 :rationale (or (get-in parsed [:proposal :rationale])
                                                (:rationale parsed)
                                                (:description parsed)
                                                "No rationale")
                                 :code code}})
                   (catch Exception e
                     (println "Warning: Could not parse LLM response as EDN")
                     (println "Error:" (.getMessage e))
                     (println "Response was:" (subs response 0 (min 200 (count response))))
                     {:analysis response
                      :opportunity "Parse error"
                      :proposal {:title "Failed to parse"
                                 :rationale response
                                 :code ";; No code generated"}}))

        ;; Create sandboxed environment
        observer-agent (agent-core/make-agent
                         {:name "llm-observer"
                          :isolation :sci
                          :permissions #{:use-tools}
                          :tools #{:list-notes :create-note! :search! :get-note
                                   :update-note! :activity-summary}})

        forked-runtime (ctx/fork-context rtc/*execution-context*)

        {:keys [runtime sci-ctx]}
        (agent-sci/create-agent-execution-context
          observer-agent
          forked-runtime
          {:tools {'list-notes app/list-notes
                   'create-note! app/create-note!
                   'search! app/search!
                   'get-note app/get-note
                   'update-note! app/update-note!
                   'delete-note! app/delete-note!
                   'activity-summary app/activity-summary}})]

    ;; Print summary
    (println "\n--- Analysis ---")
    (println (:analysis proposal))
    (println "\n--- Opportunity ---")
    (println (:opportunity proposal))
    (println "\n--- Proposal ---")
    (println "Title:" (get-in proposal [:proposal :title]))
    (println "Rationale:" (get-in proposal [:proposal :rationale]))
    (println "\n--- Proposed Code ---")
    (println (get-in proposal [:proposal :code]))
    (println "\n")

    {:runtime runtime
     :sci-ctx sci-ctx
     :agent observer-agent
     :fork-id (:fork-id forked-runtime)
     :proposal proposal
     :raw-response response}))

(defn test-proposal-code
  "Test the proposed code in the sandbox.

   Evaluates the proposal's code in the SCI sandbox and returns the result."
  [{:keys [sci-ctx proposal] :as env}]
  (let [code (get-in proposal [:proposal :code])]
    (println "Testing proposed code in sandbox...")
    (println "")
    (let [result (sandbox/eval-code sci-ctx code)]
      (println "Result:" result)
      result)))

(defn run-in-sandbox
  "Run arbitrary code in the environment's sandbox.

   Use this to explore and test the proposal interactively."
  [{:keys [sci-ctx]} code]
  (sandbox/eval-code sci-ctx code))

;; ============================================================================
;; Convenience API
;; ============================================================================

(defn demo!
  "Run a complete demo: simulate users, observe with LLM, review.

   Args:
     seed - Random seed for reproducible simulation (default: 42)"
  ([] (demo! 42))
  ([seed]
   (println "\n" (apply str (repeat 60 "=")) "\n")
   (println "       LIVING SOFTWARE DEMO")
   (println "\n" (apply str (repeat 60 "=")) "\n")

   ;; Initialize if needed
   (when (nil? app/ydb)
     (println "Initializing trial app...")
     (app/init!))

   ;; Run simulation
   (simulate-mixed seed)

   ;; Observe with LLM
   (println "\n")
   (let [env (observe-with-llm!)]
     (println "Environment created. You can now:")
     (println "  (test-proposal-code env)         - Test the proposed code")
     (println "  (run-in-sandbox env \"(list-notes)\") - Run code in sandbox")
     (println "  (app/approve! env)               - Merge changes")
     (println "  (app/reject! env)                - Discard changes")
     env)))

;; ============================================================================
;; REPL Usage
;; ============================================================================

(comment
  ;; Initialize
  (require '[dvergr.trial.app :as app])
  (require '[dvergr.trial.simulation :as sim])
  (app/init!)

  ;; Run reproducible simulation
  (sim/simulate-researcher 42 5)
  (sim/simulate-forgetful 42 10)
  (sim/simulate-mixed 42)

  ;; Check activity
  (app/activity-summary)

  ;; Run LLM observer
  (def env (sim/observe-with-llm! :debug true))

  ;; Test the proposal
  (sim/test-proposal-code env)

  ;; Or run custom code in sandbox
  (sim/run-in-sandbox env "(list-notes)")
  (sim/run-in-sandbox env "(activity-summary)")

  ;; Review and decide
  (app/show-proposal env)  ; if it has :proposal key in expected format
  (app/approve! env)
  ;; or
  (app/reject! env)

  ;; Full demo
  (def env (sim/demo! 42))

  ;; Cleanup
  (app/stop!))
