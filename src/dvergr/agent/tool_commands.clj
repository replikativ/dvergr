(ns dvergr.agent.tool-commands
  "Agent-scoped tool commands derived from the tool registry — the executing
   half of the sandbox-command surface (step 3). Each exposes one tool as a
   slash-command that runs in a TARGET AGENT's per-[room,agent] SCI sandbox:

     /clojure_eval <agent> <code>      → eval in <agent>'s session sci-ctx
     /shell        <agent> <command>   → run in <agent>'s muschel shell
     /knowledge_search <agent> <query> → search from <agent>'s ctx

   Because it runs in the agent's OWN sandbox, it shares the agent's live state
   (its defined vars) and, inside a fork room, hits the fork's branched
   world — a safe scratch space. `/clojure_eval var (+ 1 2)` IS the mini-REPL.

   Registered into `dvergr.discourse.commands`, so every frontend (TUI, web,
   Telegram) exposes them through the ONE registry.

   GATED: these execute code/shell, so a command refuses unless the host ctx
   grants `:tool-exec?`. The operator consoles (TUI, web — already backed by an
   nREPL) grant it; the Telegram handler grants it only when config opts in
   (`:telegram {:tool-commands? true}`), since Telegram is a remote surface."
  (:require [clojure.string :as str]
            [dvergr.discourse.commands :as commands]
            [dvergr.agent.room-context :as room-context]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

;; tool name → the primary string parameter the raw arg maps to. Only
;; single-dominant-string tools are exposed (a clean `<agent> <one-string>`
;; shape); structured-input tools are left to the agent's own clojure_eval.
(def ^:private exposed-tools
  {"clojure_eval"     :code
   "shell"            :command
   "knowledge_search" :query})

(defn- split-agent
  "Peel the first token as the agent id IF it names an agent with a live ctx in
   `room`; otherwise treat the whole string as the tool arg and fall back to the
   default `agent-id` (so `/clojure_eval (+ 1 2)` works in a DM). Returns
   [agent-kw arg-str]."
  [room agent-id args-str]
  (let [[w1 more] (str/split (str args-str) #"\s+" 2)
        w1k       (some-> w1 not-empty (str/replace #"^@" "") keyword)]
    (if (and w1k room (room-context/lookup (:id room) w1k))
      [w1k (or more "")]
      [agent-id (str args-str)])))

(defn- result->str [r]
  (cond
    (string? r) r
    (map? r)    (or (:content r) (:error r) (pr-str r))
    (nil? r)    "nil"
    :else       (str r)))

(defn- run-tool-command [tool-name param-kw]
  (fn [{:keys [room agent-id args-str notify! tool-exec?]}]
    (let [say (fn [m] (when notify! (notify! m)) {:handled? true :no-turn? true :reply m})]
      (cond
        (not tool-exec?)
        (say (str "/" tool-name " executes in an agent's sandbox and is disabled "
                  "on this surface."))
        (nil? room) (say "No room here.")
        :else
        (let [[aid arg] (split-agent room agent-id args-str)]
          (cond
            (nil? aid)       (say (str "Usage: /" tool-name " <agent> <" (name param-kw) ">"))
            (str/blank? arg) (say (str "Usage: /" tool-name " <agent> <" (name param-kw) ">"))
            :else
            (if-let [cc (room-context/lookup (:id room) aid)]
              ;; Bind the ROOM's ctx so the tool's dh/git/bash hit the room's
              ;; world — in a fork, the fork's branch (isolation for free).
              (binding [ec/*execution-context* (:ctx room)]
                (let [tool     (get @tools/registry tool-name)
                      tool-ctx (tools/make-context
                                {:chat-ctx      cc
                                 :sci-ctx       (:sci-ctx cc)   ; the agent's OWN session
                                 :db-conn       (:db-conn cc)
                                 :tools         {tool-name tool} ; allowlist = just this tool
                                 :isolation     :sci
                                 :execution-ctx (:ctx room)})
                      out      (result->str (tools/execute tool-name {param-kw arg} tool-ctx))]
                  (say (str "» " (name aid) " " tool-name "\n" out))))
              (say (str "@" (name aid) " has no active sandbox yet — message it once.")))))))))

(defn register!
  "Register the exposed tools as agent-scoped slash-commands (idempotent — safe
   to call again after the tool registry changes). Skips tools not present in
   the registry. Returns the registered command names."
  []
  (->> exposed-tools
       (keep (fn [[tname param]]
               (when (get @tools/registry tname)
                 (commands/register!
                  {:name          tname
                   :kind          :tool
                   :description   (str "Run " tname " in <agent>'s sandbox (agent-scoped)")
                   :argument-hint (str "<agent> <" (name param) ">")
                   :run           (run-tool-command tname param)})
                 tname)))
       vec))
