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
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [jsonista.core :as json]))

;; ============================================================================
;; Toolsets and profiles
;; ============================================================================

(def op-toolsets
  "Every `dvergr.ops` op, by toolset."
  {:room/list :rooms, :room/detail :rooms, :room/create :rooms,
   :room/delete :rooms, :room/messages :rooms, :room/post :rooms
   ;; the room's workspace files: with catalog_cases, a case pack from scratch
   :room/files :files, :room/read :files, :room/write :files

   :room/unarchive :admin, :room/purge :admin, :catalog/promote :admin, :run/resume :admin

   :room/fork :worlds, :room/diff :worlds, :room/review :worlds,
   :room/merge :worlds, :room/discard :worlds,
   :room/import :worlds, :room/export :worlds

   :workflow/start :attempts, :job/status :attempts, :job/list :attempts,
   :job/cancel :attempts, :attempt/list :attempts, :attempt/detail :attempts,
   :attempt/export :bench,
   :scorecard/list :attempts, :scorecard/detail :attempts

   :workflow/attempt :runs, :run/list :runs, :run/detail :runs

   :catalog/list :catalog, :catalog/start :catalog

   :catalog/benchmark :bench, :catalog/check :bench, :catalog/calibrate :bench,
   :catalog/export :bench, :catalog/import :bench, :catalog/cases :bench, :catalog/deploy :bench, :catalog/freeze :bench, :experiment/progress :bench,
   :experiment/report :bench

   :room/wallet :wallets, :models/list :wallets

   :agent/list :agents, :agent/config :agents, :agent/create :agents,
   :agent/update :agents, :agent/open :agents, :agent/delete :agents,
   :room/invite :agents

   :room/stats :system, :system/stats :system})

(def coding-toolsets
  "Registry tools by toolset. `clojure_eval` is the room's REPL, the
   programming model; the rest duplicate what a host client already has
   (files, shell, search), so they are opt-in."
  {"clojure_eval" :repl
   "repl_describe" :describe})

(def toolsets
  "Every toolset, with what it is for."
  {:rooms    "Rooms: create, list, read messages, post a task"
   :files    "A room's workspace files: list, read (text or base64), write a batch as one commit"
   :worlds   "Forks of a room: fork, diff, merge, discard"
   :attempts "Run a task N times per model on forks, as a job; Attempts, Scorecards"
   :runs     "The blocking workflow_attempt, and Runs (lower level)"
   :catalog  "Workflows with their own checker and benchmark set"
   :bench    "Benchmark models on a workflow's benchmark set; experiment progress and reports"
   :wallets  "Budgets and model prices"
   :repl     "The room's Clojure REPL (SCI sandbox) with dvergr's programming model"
   :describe "repl_describe: the REPL's API (dvergr namespaces, functions, docs), for REPL-first clients"
   :agents   "Agent administration"
   :system   "Daemon and room statistics"
   :admin    "Archived rooms: bring one back, or purge it for good"
   :code     "File, search, shell and task tools, for hosts without their own"
   :host     "Ops the host registered (dvergr.ops/register-op!)"
   :extra    "Tools registered at runtime (channels)"})

(def profiles
  "Named selections. `:read-only?` keeps only tools annotated read-only.
   `:repl-toolsets` are the toolsets whose ops the REPL's `dvergr.ops` may
   call when they differ from the listed ones (`dvergr.mcp.repl`)."
  {:offload  {:toolsets #{:rooms :worlds :attempts :catalog :wallets :repl}}
   ;; :files: a case pack's table goes in with room_write (catalog_cases reads it)
   :bench    {:toolsets #{:rooms :files :attempts :catalog :bench :wallets}}
   ;; REPL-first: the whole API as `dvergr.ops` functions in `clojure_eval`
   :code     {:toolsets #{:repl :describe}
              :repl-toolsets #{:rooms :files :worlds :attempts :catalog :bench :wallets}}
   :readonly {:toolsets #{:rooms :files :worlds :attempts :catalog :bench :wallets :agents :system}
              :read-only? true}
   :admin    {:toolsets (set (keys toolsets))}})

(def default-profile :offload)

(defn tool-toolset
  "The toolset of the tool called `tool-name`; `op` when it is a `dvergr.ops`
   op (`:host` when a host registered it), else a registry tool (`:code`
   unless listed) or a runtime one."
  [tool-name op registry?]
  (cond
    op (get op-toolsets op :host)
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
  [{:keys [profile toolsets room tools]}]
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
    (cond-> (-> base
                (update :toolsets into extra)
                (assoc :profile pk))
      (:repl-toolsets base) (update :repl-toolsets into extra)
      ;; a connection pinned to one room, seeing exactly the tools named
      (not (str/blank? (str room))) (assoc :room (str room))
      (seq tools) (assoc :tools (set (map (comp str/trim name)
                                          (if (string? tools) (str/split tools #",") tools)))))))

(defn principal-toolsets
  "The toolsets `principal` may be shown, from its `:principal/scopes`: nil
   (no cap) for the local principal, every toolset with `:dvergr/admin`, else
   the toolsets its `:toolset/<name>` scopes name (none without any). Never
   `:extra` for anyone but the local principal: a tool registered at runtime
   (`dvergr.mcp.server/register-tool!`) has no authorization of its own."
  [principal]
  (let [scopes (set (:principal/scopes principal))]
    (when-not (= :local (:principal/kind principal))
      (disj (if (contains? scopes :dvergr/admin)
              (set (keys toolsets))
              (into #{} (keep #(when (= "toolset" (namespace %)) (keyword (name %)))) scopes))
            :extra))))

(defn capped
  "`selection` as far as `principal` may see: its toolsets (and REPL toolsets)
   intersected with `principal-toolsets`, which also bounds `:tools` (kept as
   `:allowed`), and read-only with the `:dvergr/read-only` scope. A client
   narrows its selection in `_meta`; this is what keeps it from widening it."
  [principal selection]
  (let [allowed (principal-toolsets principal)
        read-only? (contains? (set (:principal/scopes principal)) :dvergr/read-only)]
    (cond-> selection
      allowed (-> (update :toolsets set/intersection allowed)
                  (assoc :allowed allowed))
      (and allowed (:repl-toolsets selection)) (update :repl-toolsets set/intersection allowed)
      read-only? (assoc :read-only? true))))

(defn repl-selection
  "The selection whose ops a REPL of `selection` may call: its
   `:repl-toolsets` when it names them, else what it lists."
  [selection]
  (cond-> selection
    (:repl-toolsets selection) (assoc :toolsets (:repl-toolsets selection))))

(defn visible?
  "Whether a tool definition (with `:dvergr/toolset`) is in `selection`. A
   selection with `:tools` (a set of names) sees exactly those; a capped one
   (`capped`) never sees a toolset outside its `:allowed`."
  [selection tool-def]
  (and (or (nil? (:allowed selection))
           (contains? (:allowed selection) (:dvergr/toolset tool-def)))
       (if-let [only (:tools selection)]
         (contains? only (:name tool-def))
         (contains? (:toolsets selection) (:dvergr/toolset tool-def)))
       (or (not (:read-only? selection))
           (true? (get-in tool-def [:annotations :readOnlyHint])))))

(defn pinned-view
  "`tool-def` as a session pinned to one room (`:room` in `selection`) shows
   it: without its `room` parameter, which the session supplies."
  [selection tool-def]
  (if (and (:room selection) (get-in tool-def [:inputSchema :properties :room]))
    (-> tool-def
        (update-in [:inputSchema :properties] dissoc :room)
        (update-in [:inputSchema :required] #(vec (remove #{"room" :room} %))))
    tool-def))

(defn pinned-args
  "`args` of a call in a session pinned to a room: its `room` is the pinned
   one; naming another is refused. Unpinned sessions pass args through."
  [selection args]
  (if-let [pinned (:room selection)]
    (let [given (some-> (:room args) str)]
      (when (and given (not= given (str pinned)))
        (throw (ex-info (str "This connection works in room " pinned " only")
                        {:type ::room-pinned :room given :pinned pinned})))
      (assoc args :room (str pinned)))
    args))

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
   ;; overwrites and removes files; the same batch again commits nothing
   :room/write       {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :room/create      {:destructiveHint false :idempotentHint false :openWorldHint false}
   :room/delete      {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :room/unarchive   {:destructiveHint false :idempotentHint true  :openWorldHint false}
   :room/purge       {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :workflow/attempt {:destructiveHint false :idempotentHint false :openWorldHint true}
   :workflow/start   {:destructiveHint false :idempotentHint false :openWorldHint true}
   :job/cancel       {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :catalog/start    {:destructiveHint false :idempotentHint false :openWorldHint true}
   :catalog/benchmark {:destructiveHint false :idempotentHint false :openWorldHint true}
   :catalog/promote  {:destructiveHint false :idempotentHint true  :openWorldHint false}
   :run/resume       {:destructiveHint false :idempotentHint false :openWorldHint true}
   :catalog/import   {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :catalog/cases    {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   :catalog/deploy   {:destructiveHint false :idempotentHint false :openWorldHint true}
   :catalog/freeze   {:destructiveHint false :idempotentHint true  :openWorldHint false}
   :room/fork        {:destructiveHint false :idempotentHint false :openWorldHint false}
   :room/merge       {:destructiveHint true  :idempotentHint false :openWorldHint false}
   :room/discard     {:destructiveHint true  :idempotentHint true  :openWorldHint false}
   ;; Import reaches a local checkout or a remote over the network.
   :room/import      {:destructiveHint false :idempotentHint false :openWorldHint true}
   :room/export      {:destructiveHint false :idempotentHint true  :openWorldHint false}
   ;; With `file`, overwrites exports/<file>.
   :attempt/export   {:destructiveHint true  :idempotentHint true  :openWorldHint false}
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
     "task_list" ro, "budget" ro, "clj_kondo" ro, "repl_describe" ro
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
    "- Long work is a job: start it, then `job_status` (waits up to 25 s per call; a benchmark's shows cells done of total), `job_cancel`."
    "- Catalog: `catalog_list` shows workflows with their own checker; `catalog_start` runs one on its"
    "  benchmark set (no room: compare models) or on your room's files, scored per attempt."
    "- Money: every model call is billed to the room's wallet (`room_wallet`); a spent wallet refuses work."
    "- REPL: `clojure_eval` evaluates Clojure in the room's sandbox (SCI). State persists per room. Its `dvergr.ops` namespace holds every op this connection may call, as functions (`(dir dvergr.ops)`, `(doc dvergr.ops/job-status)`): one program instead of a chain of tool calls."
    "- Results are JSON. Large results are cut; ask for less (limits, detail ops) rather than more."
    ""
    "Tools are grouped into toolsets. This connection's profile decides which are visible."]))
