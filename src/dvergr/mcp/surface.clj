(ns dvergr.mcp.surface
  "What an MCP client sees of dvergr: which tools (toolsets and profiles), how
   each is described to the client (title, annotations), and how results are
   encoded. The tools themselves are derived from `dvergr.ops` and the
   `dvergr.tools` registry (`dvergr.mcp.server`); this namespace only classifies
   and renders them, as data, so that a test can require every op and every
   registry tool to be classified.

   Clients differ widely (tool caps around 40, 60 s call timeouts, tools-only
   automation clients, strict-schema model APIs), so the default profile is
   small, every tool carries annotations, and every result is JSON text plus
   the same value as `structuredContent`."
  (:require [clojure.string :as str]
            [jsonista.core :as json]))

;; ============================================================================
;; Toolsets and profiles
;; ============================================================================

(def op-toolsets
  "Every `dvergr.ops` op, by toolset."
  {:room/list :rooms, :room/detail :rooms, :room/create :rooms,
   :room/delete :rooms, :room/messages :rooms, :room/post :rooms

   :room/fork :worlds, :room/diff :worlds, :room/review :worlds,
   :room/merge :worlds, :room/discard :worlds

   :workflow/start :attempts, :job/status :attempts, :job/list :attempts,
   :job/cancel :attempts, :attempt/list :attempts, :attempt/detail :attempts,
   :scorecard/list :attempts, :scorecard/detail :attempts

   :workflow/attempt :runs, :run/list :runs, :run/detail :runs

   :catalog/list :catalog, :catalog/start :catalog

   :room/wallet :wallets, :models/list :wallets

   :agent/list :agents, :agent/config :agents, :agent/create :agents,
   :agent/update :agents, :agent/open :agents, :agent/delete :agents,
   :room/invite :agents

   :room/stats :system, :system/stats :system})

(def coding-toolsets
  "Registry tools by toolset. `clojure_eval` is the room's REPL, the
   programming model; the rest duplicate what a host client already has
   (files, shell, search), so they are opt-in."
  {"clojure_eval" :repl})

(def toolsets
  "Every toolset, with what it is for."
  {:rooms    "Rooms: create, list, read messages, post a task"
   :worlds   "Forks of a room: fork, diff, merge, discard"
   :attempts "Run a task N times per model on forks, as a job; Attempts, Scorecards"
   :runs     "The blocking workflow_attempt, and Runs (lower level)"
   :catalog  "Workflows with their own checker and benchmark set"
   :wallets  "Budgets and model prices"
   :repl     "The room's Clojure REPL (SCI sandbox) with dvergr's programming model"
   :agents   "Agent administration"
   :system   "Daemon and room statistics"
   :code     "File, search, shell and task tools, for hosts without their own"
   :extra    "Tools registered at runtime (channels)"})

(def profiles
  "Named selections. `:read-only?` keeps only tools annotated read-only."
  {:offload  {:toolsets #{:rooms :worlds :attempts :catalog :wallets :repl}}
   :readonly {:toolsets #{:rooms :worlds :attempts :catalog :wallets :agents :system}
              :read-only? true}
   :admin    {:toolsets (set (keys toolsets))}})

(def default-profile :offload)

(defn tool-toolset
  "The toolset of the tool called `tool-name`; `op` when it is a `dvergr.ops`
   op, else a registry tool (`:code` unless listed) or a runtime one."
  [tool-name op registry?]
  (cond
    op (get op-toolsets op :extra)
    registry? (get coding-toolsets tool-name :code)
    :else :extra))

(def ^:private toolsets-keys (set (keys toolsets)))

(defn- parse-toolsets [x]
  (when x
    (->> (if (string? x) (str/split x #",") x)
         (map (comp keyword str/trim name))
         (remove #(= "" (name %)))
         set)))

(defn selection
  "The session's tool selection from a profile name and/or an explicit toolset
   list (strings or keywords; toolsets are added to the profile's). Unknown
   names throw, so a misconfigured client fails at connect, not later."
  [{:keys [profile toolsets]}]
  (let [pk (keyword (or (some-> profile name str/trim not-empty) (name default-profile)))
        base (or (get profiles pk)
                 (throw (ex-info (str "Unknown dvergr MCP profile: " (name pk)
                                      " (known: " (str/join ", " (map name (keys profiles))) ")")
                                 {:type ::unknown-profile :profile pk})))
        extra (parse-toolsets toolsets)
        unknown (remove toolsets-keys extra)]
    (when (seq unknown)
      (throw (ex-info (str "Unknown dvergr MCP toolset(s): " (str/join ", " (map name unknown)))
                      {:type ::unknown-toolset :toolsets (vec unknown)})))
    (-> base
        (update :toolsets into extra)
        (assoc :profile pk))))

(defn visible?
  "Whether a tool definition (with `:dvergr/toolset`) is in `selection`."
  [selection tool-def]
  (and (contains? (:toolsets selection) (:dvergr/toolset tool-def))
       (or (not (:read-only? selection))
           (true? (get-in tool-def [:annotations :readOnlyHint])))))

;; ============================================================================
;; Annotations
;; ============================================================================

(def ^:private read-annotations
  {:readOnlyHint true :destructiveHint false :idempotentHint true :openWorldHint false})

(def write-annotations
  "Every write op's hints. `destructiveHint` marks ops that delete or
   overwrite (clients confirm them); `openWorldHint` marks ops that start agent
   or model work."
  {:room/post        {:destructiveHint false :idempotentHint false :openWorldHint true}
   :room/create      {:destructiveHint false :idempotentHint false :openWorldHint false}
   :room/delete      {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :workflow/attempt {:destructiveHint false :idempotentHint false :openWorldHint true}
   :workflow/start   {:destructiveHint false :idempotentHint false :openWorldHint true}
   :job/cancel       {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :catalog/start    {:destructiveHint false :idempotentHint false :openWorldHint true}
   :room/fork        {:destructiveHint false :idempotentHint false :openWorldHint false}
   :room/merge       {:destructiveHint true  :idempotentHint false :openWorldHint false}
   :room/discard     {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :agent/create     {:destructiveHint false :idempotentHint false :openWorldHint false}
   :agent/update     {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :agent/open       {:destructiveHint false :idempotentHint false :openWorldHint false}
   :room/invite      {:destructiveHint false :idempotentHint true  :openWorldHint false}
   :agent/delete     {:destructiveHint true  :idempotentHint true  :openWorldHint false}})

(def coding-annotations
  "Every registry tool's hints. A tool missing here (registered later, e.g. by
   a channel) gets the conservative default: destructive, open world."
  (let [ro read-annotations
        w  (fn [destructive? open?] {:readOnlyHint false :destructiveHint destructive?
                                     :idempotentHint false :openWorldHint open?})]
    {"read_file" ro, "glob" ro, "grep" ro, "code_query" ro, "knowledge_search" ro,
     "task_list" ro, "budget" ro, "clj_kondo" ro
     "clojure_eval" (w true true), "shell" (w true true),
     "write_file" (w true false), "edit_file" (w true false), "clojure_edit" (w true false),
     "run_tests" (w false false), "knowledge_add" (w false false),
     "propose_change" (w false false), "task_create" (w false false),
     "task_update" (w false false), "update_agent_profile" (w true false),
     "spawn_agent" (w false true), "llm_call" (w false true)
     ;; registered when their namespaces load
     "mail_sync" (w false true), "mail_inbox" ro, "mail_read" ro, "mail_search" ro,
     "schedule_create" (w false false), "schedule_cancel" (w true false), "schedule_list" ro,
     "renewal_plan" (w false false)}))

(defn title
  "A human title from a tool name: \"room_post\" → \"Room post\"."
  [tool-name]
  (str/capitalize (str/replace tool-name "_" " ")))

(defn op-annotations [op kind]
  (assoc (if (= :read kind)
           read-annotations
           (merge {:readOnlyHint false} (get write-annotations op
                                             {:destructiveHint true :idempotentHint false
                                              :openWorldHint true})))
         :title (title (str (namespace op) "_" (name op)))))

(defn tool-annotations [tool-name]
  (assoc (get coding-annotations tool-name
              {:readOnlyHint false :destructiveHint true :idempotentHint false :openWorldHint true})
         :title (title tool-name)))

;; ============================================================================
;; Results
;; ============================================================================

(def max-text-chars
  "Result text beyond this is cut (Claude Code caps a result at 25k tokens;
   ~4 chars per token leaves room for the rest of the turn)."
  60000)

(def ^:private json-mapper
  (json/object-mapper
   {:encode-key-fn (fn [k] (if (keyword? k) (subs (str k) 1) (str k)))}))

(defn- ->json [data]
  (try (json/write-value-as-string data json-mapper)
       (catch Exception _ (json/write-value-as-string (pr-str data)))))

(defn- cut [^String s]
  (if (<= (count s) max-text-chars)
    s
    (str (subs s 0 max-text-chars)
         "\n[dvergr: result cut at " max-text-chars " of " (count s)
         " chars; ask for less, e.g. a limit or a detail op]")))

(defn data-result
  "An op's data as an MCP tool result: JSON text, and the same value as
   `structuredContent` (an object; a non-map is wrapped as `{result: …}`)."
  [data]
  (let [text (->json data)]
    (if (< max-text-chars (count text))
      {:content [{:type "text" :text (cut text)}] :isError false}
      {:content [{:type "text" :text text}]
       :structuredContent (json/read-value (->json (if (map? data) data {:result data})))
       :isError false})))

(defn error-result [msg]
  {:content [{:type "text" :text (str msg)}] :isError true})

(defn tool-result
  "A `dvergr.tools` result (`{:type :success :content …}` or
   `{:type :error :error … :suggestion …}`) as an MCP tool result."
  [{:keys [type content error suggestion] :as r}]
  (cond
    (= :error type) (error-result (cond-> (str error) suggestion (str "\n" suggestion)))
    (string? content) {:content [{:type "text" :text (cut content)}] :isError false}
    :else (data-result (dissoc r :authorization))))

;; ============================================================================
;; Instructions
;; ============================================================================

(def instructions
  "Server instructions for the client (kept under 2 KB)."
  (str/join
   "\n"
   ["dvergr runs agentic work in rooms: a room holds a database, a git workspace and a Clojure REPL."
    "Every call names its room (`room`: id or slug); there is no session state."
    ""
    "- Forks: `room_fork` makes an isolated copy-on-write world of a room; `room_diff` shows what changed;"
    "  `room_merge` adopts it into its parent; `room_discard` drops it."
    "- Attempts: `workflow_start` runs a task several times per model, each on its own fork, as a job;"
    "  poll `job_status {job, wait-ms}`. The result has per-attempt status, spend and a review of each world."
    "  Adopt one with `room_merge {room: world, expect-state: review.state}`, `room_discard` the rest."
    "- Long work is a job: start it, then `job_status` (waits up to 25 s per call), `job_cancel`."
    "- Catalog: `catalog_list` shows workflows with their own checker; `catalog_start` runs one on its"
    "  benchmark set (no room: compare models) or on your room's files, scored per attempt."
    "- Money: every model call is billed to the room's wallet (`room_wallet`); a spent wallet refuses work."
    "- REPL: `clojure_eval` evaluates Clojure in the room's sandbox (SCI). State persists per room."
    "- Results are JSON. Large results are cut; ask for less (limits, detail ops) rather than more."
    ""
    "Tools are grouped into toolsets. This connection's profile decides which are visible."]))
