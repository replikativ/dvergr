(ns dvergr.agent.ops
  "Shared agent-management operations — the deduplicated layer behind the web
   and TUI agent-config UIs (mirrors `dvergr.rooms` / `dvergr.rooms.forks`).

   An agent is two coupled things:
     1. a durable **actor row** (`dvergr.actors`, datahike-authoritative) that
        carries identity + the model/provider/skills/budget config, and
     2. a **persona prompt** (`dvergr.agent.persona`, a project-local markdown
        file under `.dvergr/agents/<id>.md`, built-in resource as fallback).

   These ops keep both in sync so a UI edit is a single call. The actor row is
   the source of truth (see [[fork-isolation-arc]] config foundation); the
   daemon's join path reads it (see `dvergr.orchestration.daemon/agent-join-config`),
   so edits take effect on the next room join without a restart.

   ctx-bound: every fn resolves the chat-db conn from the bound
   `*execution-context*` and returns nil when there is none."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.agent.persona :as persona]
            [dvergr.system.db :as sdb]
            [taoensso.telemere :as tel]))

;; RF5 S3: actor identity lives in the global system-db, not the per-room
;; chat-db. system-db is a plain (un-forked) datahike with its own conn-atom,
;; so it resolves without a bound execution context.
(defn- chat-conn [] (sdb/get-conn))

(defn- ->id [id] (if (keyword? id) id (keyword id)))

;; The config sub-map keys that live inside :actor/config (vs identity fields
;; :name / :skills which are top-level actor attributes).
(def ^:private config-keys [:provider :model :description :budget-dollars :rooms :tools])

;; ============================================================================
;; Read
;; ============================================================================

(defn get-agent
  "Full agent view: identity + config (flattened out of :actor/config) +
   persona prompt and where it resolves from. nil if no such actor row."
  [id]
  (when-let [conn (chat-conn)]
    (let [id (->id id)]
      (when-let [a (actors/lookup conn id)]
        (let [cfg (:config a)]
          (merge {:id             id
                  :name           (:name a)
                  :status         (:status a)
                  :tags           (:skills a)
                  :online?        (actors/online? id)
                  :prompt         (persona/resolve-prompt id)
                  :persona-source (persona/source id)}
                 (select-keys cfg config-keys)))))))

(defn list-agents
  "All :agent actor rows (durable — includes offline/retired), each as a
   compact map for the roster. Sorted by id. [] with no ctx."
  []
  (if-let [conn (chat-conn)]
    (->> (actors/list-actors conn :kind :agent)
         (mapv (fn [a]
                 (let [cfg (:config a)]
                   {:id             (:id a)
                    :name           (:name a)
                    :status         (:status a)
                    :tags           (:skills a)
                    :online?        (actors/online? (:id a))
                    :provider       (:provider cfg)
                    :model          (:model cfg)
                    :description    (:description cfg)
                    :persona-source (persona/source (:id a))})))
         (sort-by (comp name :id))
         vec)
    []))

;; ============================================================================
;; Write
;; ============================================================================

(defn create-agent!
  "Create a new :agent actor row (+ optional persona prompt). `id` may be a
   keyword or string. Returns the new agent view (`get-agent`), or nil if the
   id already exists / there's no ctx.

   Accepts the UI-level field map: :id :name :model :provider :tags
   :description :budget-dollars :rooms :prompt."
  [{:keys [id name model provider tags description budget-dollars rooms prompt]}]
  (when-let [conn (chat-conn)]
    (let [id (->id id)]
      (when (and id (not (actors/lookup conn id)))
        (let [config (cond-> {}
                       provider                (assoc :provider provider)
                       model                   (assoc :model model)
                       (not (str/blank? description)) (assoc :description description)
                       budget-dollars          (assoc :budget-dollars budget-dollars)
                       (seq rooms)             (assoc :rooms rooms))]
          (actors/spawn-agent! conn
                               (cond-> {:id          id
                                        :name        (or (not-empty name) (clojure.core/name id))
                                        :profile-ref (str (clojure.core/name id) ".md")
                                        :skills      (set tags)
                                        :config      config
                                        :status      :online}
                                 (not (str/blank? prompt)) (assoc :system-prompt prompt)))
          (tel/log! {:id :agent-ops/created :data {:id id}} "Agent created")
          (get-agent id))))))

(defn update-agent!
  "Patch an existing agent. Accepts any subset of the UI-level field map
   (:name :model :provider :tags :description :budget-dollars :rooms :prompt).
   Config-sub-map fields are merged onto the existing :actor/config (so a
   partial patch never clobbers untouched config keys); :prompt is stored on the
   actor row (`:actor/system-prompt`). Returns the updated `get-agent`, or nil."
  [id patch]
  (when-let [conn (chat-conn)]
    (let [id       (->id id)
          existing (actors/lookup conn id)]
      (when existing
        (let [cfg-updates (select-keys patch config-keys)
              actor-patch (cond-> {}
                            (contains? patch :name)   (assoc :name (:name patch))
                            (contains? patch :prompt) (assoc :system-prompt (or (:prompt patch) ""))
                            (seq cfg-updates)         (assoc :config (merge (:config existing)
                                                                            cfg-updates)))]
          (when (seq actor-patch)
            (actors/update-actor! conn id actor-patch))
          ;; Skills are cardinality/many — a plain set-assert only adds, never
          ;; retracts. Diff explicitly so a tag edit truly replaces the set.
          (when (contains? patch :tags)
            (let [new-tags (set (:tags patch))
                  old-tags (set (:skills existing))]
              (doseq [s (set/difference old-tags new-tags)]
                (actors/remove-skill! conn id s))
              (doseq [s (set/difference new-tags old-tags)]
                (actors/add-skill! conn id s))))
          (tel/log! {:id :agent-ops/updated :data {:id id :fields (keys patch)}}
                    "Agent updated")
          (get-agent id))))))

(defn delete-agent!
  "Hard-delete an agent: retract its actor row (the stored system prompt goes with
   it; a built-in `resources/agents/<id>.md` default is left untouched — it's on
   the classpath). Does NOT tear down a running participant; the caller should
   `daemon/stop-agent!` first. Returns :deleted, or nil."
  [id]
  (when-let [conn (chat-conn)]
    (let [id (->id id)]
      (when (actors/lookup conn id)
        (dh/transact conn [[:db/retractEntity [:actor/id id]]])
        (tel/log! {:id :agent-ops/deleted :data {:id id}} "Agent deleted")
        :deleted))))
