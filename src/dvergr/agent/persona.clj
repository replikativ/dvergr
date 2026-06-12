(ns dvergr.agent.persona
  "Agent persona resolution + writing — the system prompt for an agent.

   DB-FIRST, with a built-in fallback:
     1. the actor row's `:actor/system-prompt` (Datahike, AUTHORITATIVE) — managed
        only through the API (`dvergr.agent.ops`, the web/TUI config, the REPL),
        so it is value-semantic (forks with the row) and versioned.
     2. `resources/agents/<id>.md` — dvergr's built-in default (classpath, read-only).

   The prompt deliberately does NOT live in an unmanaged file: agents mutate dvergr
   through controlled surfaces, not by scribbling markdown outside the substrate.
   A consuming project ships its own defaults as `resources/agents/<id>.md` on its
   classpath, or sets prompts at runtime via the API (which persists to the DB).

   The single home for persona loading — `daemon/load-agent-prompt` and the tools
   layer both delegate here. ctx-bound: the DB lookup needs a bound execution
   context; with none, only the built-in resource is consulted."
  (:require [clojure.java.io :as io]
            [dvergr.actors :as actors]
            [dvergr.discourse.definitions :as defs]
            [dvergr.system.db :as sdb]))

;; RF5 S3: the actor row (and its :actor/system-prompt) lives in the global
;; system-db, which resolves without a bound execution context.
(defn- chat-conn [] (sdb/get-conn))

(defn- db-prompt
  "The actor row's stored system prompt for `id`, or nil (also nil with no ctx)."
  [id]
  (when-let [conn (chat-conn)]
    (some-> (actors/lookup conn id) :system-prompt not-empty)))

(defn- builtin-resource [id]
  (io/resource (str "agents/" (name id) ".md")))

(defn resolve-prompt
  "System prompt for `id`: the stored actor-row prompt if set, else the built-in
   agent definition's BODY (frontmatter stripped, scope chain honored), else nil."
  [id]
  (or (db-prompt id)
      (defs/body "agents" (name id))))

(defn source
  "Where `id`'s prompt resolves from: :db | :builtin | :none — for the UI to show
   whether an agent uses a stored (overridden) or the shipped prompt."
  [id]
  (cond
    (db-prompt id)        :db
    (builtin-resource id) :builtin
    :else                 :none))

(defn write-prompt!
  "Persist `content` as `id`'s system prompt on the actor row (Datahike). Returns
   the actor map, or nil if there is no chat DB / no such actor. Never writes a
   file — the prompt is managed state."
  [id content]
  (when-let [conn (chat-conn)]
    (actors/update-actor! conn id {:system-prompt (or content "")})))
