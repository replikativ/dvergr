(ns dvergr.actors
  "Durable actor identity — the Datahike-backed source of truth for which
   actors *exist*, what skills they provide, and which channels they reach.
   An actor row survives daemon restart.

   Identity (this ns) is durable; **presence is room membership** — an actor
   is `online?` iff its participant is joined to a registered room (see
   `online?`). There is no separate runtime registry of identities.

   Actor kinds:
     :agent     — LLM-driven (Participant at runtime; spawned from a profile)
     :human     — user account (tasks land in their inbox; reply via web/Telegram)
     :external  — MCP endpoint (invocation = tools/call)
     :service   — built-in daemon-level service

   Phase B introduces only :agent. Phases D wire the other kinds in.

   Usage:
     (a/spawn-agent! conn {:id :scribe :name \"Scribe\"
                           :profile-ref \"scribe.md\"
                           :skills #{:writing :prose}
                           :config {:provider :fireworks :model \"...\"}})
     (a/lookup conn :scribe)
     (a/list-actors conn)
     (a/list-actors conn :kind :agent :status :online)
     (a/online? :scribe)              ; consults the runtime registry
     (a/dismiss! conn :scribe)        ; flags status :retired (keeps the row)"
  (:require [clojure.edn :as edn]
            [datahike.api :as dh]
            [dvergr.room.registry :as rreg]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; EDN field helpers
;; ============================================================================

(defn- ->edn-str [m]
  (when (some? m) (pr-str m)))

(defn- <-edn-str [s]
  (when (and s (not= s ""))
    ;; Be defensive: a field that was serialized with non-EDN content
    ;; (e.g. an old row whose :config captured #object fns) must not
    ;; crash the whole actor read. Treat unreadable fields as nil.
    (try (edn/read-string s)
         (catch Exception _ nil))))

;; ============================================================================
;; Hydration
;; ============================================================================

(def ^:private pull-pattern
  [:actor/id :actor/kind :actor/name :actor/skills
   :actor/profile-ref :actor/system-prompt :actor/status :actor/created-at
   :actor/cost :actor/config :actor/external-refs
   :actor/skill-priorities])

(defn- ent->actor
  "Turn a Datahike actor entity (pull-pattern) into a normalized map."
  [ent]
  (when ent
    (cond-> {:id                (:actor/id ent)
             :kind              (:actor/kind ent)
             :name              (:actor/name ent)
             :skills            (set (:actor/skills ent))
             :profile-ref       (:actor/profile-ref ent)
             :system-prompt     (:actor/system-prompt ent)
             :status            (:actor/status ent)
             :created-at        (:actor/created-at ent)
             :cost              (<-edn-str (:actor/cost ent))
             :config            (<-edn-str (:actor/config ent))
             :external-refs     (<-edn-str (:actor/external-refs ent))
             :skill-priorities  (<-edn-str (:actor/skill-priorities ent))}
      true (->> (filter (fn [[_ v]] (some? v))) (into {})))))

(defn- actor->tx
  "Build the Datahike entity map for an actor."
  [{:keys [id kind name skills profile-ref system-prompt status created-at
           cost config external-refs skill-priorities]}]
  (cond-> {:actor/id         id
           :actor/kind       (or kind :agent)
           :actor/status     (or status :online)
           :actor/created-at (or created-at (java.util.Date.))}
    name                  (assoc :actor/name name)
    (seq skills)          (assoc :actor/skills (set skills))
    profile-ref           (assoc :actor/profile-ref profile-ref)
    system-prompt         (assoc :actor/system-prompt system-prompt)
    cost                  (assoc :actor/cost (->edn-str cost))
    config                (assoc :actor/config (->edn-str config))
    external-refs         (assoc :actor/external-refs (->edn-str external-refs))
    skill-priorities      (assoc :actor/skill-priorities (->edn-str skill-priorities))))

;; ============================================================================
;; Read API
;; ============================================================================

(defn lookup
  "Return the actor entity for the given id, or nil if not present."
  [conn id]
  (-> (dh/q '[:find (pull ?e pattern) .
              :in $ ?id pattern
              :where [?e :actor/id ?id]]
            @conn id pull-pattern)
      ent->actor))

(defn list-actors
  "List actor entities. Optional filters: :kind, :status, :skill."
  [conn & {:keys [kind status skill]}]
  (->> (dh/q '[:find [(pull ?e pattern) ...]
               :in $ pattern
               :where [?e :actor/id _]]
             @conn pull-pattern)
       (map ent->actor)
       (filter (fn [a]
                 (and (or (nil? kind)   (= kind (:kind a)))
                      (or (nil? status) (= status (:status a)))
                      (or (nil? skill)  (contains? (:skills a) skill)))))
       vec))

(defn online?
  "Is the actor currently reachable — i.e. a live participant in some
   registered room? Answers \"can I reach them right now\", not \"do they
   exist\".

   Presence is room membership, NOT a separate table: an :agent is online
   iff its participant is joined to a room (`Room`'s `:participants`).
   `:human`/`:external` actors are reachable via a connected adapter; that
   wiring lands with the adapter work."
  [id]
  (boolean
   (when (ec/execution-context-bound?)
     (some (fn [room] (contains? @(:participants room) id))
           (rreg/list-rooms)))))

(defn online-ids
  "Set of actor ids currently present (joined to some registered room).
   Pure presence — no Datahike read, no conn needed. Empty with no ctx."
  []
  (if-not (ec/execution-context-bound?)
    #{}
    (into #{}
          (mapcat (fn [room] (keys @(:participants room))))
          (rreg/list-rooms))))

(defn online-actors
  "Live agents (present in some room) projected through their durable
   actor rows: a vector of {:id :status :tags :description}. Resolves the
   chat-db conn from the bound execution context; members without an
   actor row (transient ask-* askers, the :_system drain) are dropped.

   This is the actors ⋈ room-membership view that replaces the old
   the old in-context agent registry. Presence needs a bound ctx (room
   membership); the durable actor rows resolve from the global system-db."
  []
  (let [conn (when (ec/execution-context-bound?) (sdb/get-conn))]
    (if-not conn
      []
      (->> (online-ids)
           (keep (fn [id]
                   (when-let [a (lookup conn id)]
                     {:id          id
                      :status      (or (:status a) :online)
                      :tags        (:skills a)
                      :description (:description (:config a))})))
           vec))))

;; ============================================================================
;; Write API
;; ============================================================================

(defn spawn-agent!
  "Write a new :agent actor row. Idempotent on :id — upserts.

   Required: :id (keyword)
   Optional: :name :profile-ref :skills :config :cost :external-refs :status.

   Returns the normalized actor map. Does NOT construct the runtime
   Participant — that happens in the daemon's agent boot path (which
   reads from list-actors on startup and adds runtime spawns going
   forward). The runtime registry is updated by the daemon, not here."
  [conn opts]
  (let [actor (assoc opts :kind :agent)
        tx    (actor->tx actor)]
    (dh/transact conn [tx])
    (tel/log! {:id :actors/spawned :data {:id (:id actor)
                                          :skills (:skills actor)}}
              "Actor spawned")
    (lookup conn (:id actor))))

(defn spawn-human!
  "Write a new :human actor row. Admin-only API — the assumption is
   the caller has shell/REPL access to the daemon (web sign-up is
   phase E). The human row is just identity + a link table to the
   channels they reach.

   Required: :id (keyword) AND :external-refs (must have at least one)
   Optional: :name :skills :cost :skill-priorities.

   Required external-refs example:
     {:telegram 123456789}              ; auto-promotes Telegram messages
     {:email \"alice@example.com\"}     ; for notifications (phase E)
     {:slack \"U7QKD23F\"}              ; future."
  [conn opts]
  (let [refs (:external-refs opts)]
    (assert (:id opts) ":id is required for spawn-human!")
    (assert (map? refs) "spawn-human! requires :external-refs map")
    (assert (seq refs) "spawn-human! requires at least one external-ref")
    (let [actor (assoc opts :kind :human)
          tx    (actor->tx actor)]
      (dh/transact conn [tx])
      (tel/log! {:id :actors/human-spawned
                 :data {:id (:id actor)
                        :refs (set (keys refs))}}
                "Human spawned")
      (lookup conn (:id actor)))))

(defn lookup-by-external-ref
  "Find the actor whose :external-refs maps `channel` to `value`
   (e.g. :telegram → a chat-id). Linear scan — :external-refs is an EDN
   blob, not an indexed attribute — fine at daemon-scale actor counts.
   Returns the normalized actor map, or nil."
  [conn channel value]
  (->> (list-actors conn)
       (filter (fn [a] (= value (get (:external-refs a) channel))))
       first))

(defn ensure-external-actor!
  "Idempotently materialize an actor for an external user reached over
   `channel` (e.g. :telegram) at `value` (the channel-native id, e.g. a
   Telegram chat-id). Reverse-looks-up by external-ref; if absent, creates
   a :human row with `:external-refs {channel value}` and a derived id
   `:<channel>-<value>`. Optional :name seeds the display name.

   This is the inbound-adapter foundation: every external sender gets a
   durable identity so agents can reply `:to` them and the outbound adapter
   can route via their external-refs. Identity-merging across channels (one
   human, many refs) stays an admin concern via `spawn-human!`.

   Returns the actor map."
  [conn channel value & {:keys [name]}]
  (or (lookup-by-external-ref conn channel value)
      (let [id (keyword (str (clojure.core/name channel) "-" value))]
        (spawn-human! conn (cond-> {:id            id
                                    :external-refs {channel value}}
                             name (assoc :name name))))))

(defn dismiss!
  "Flag the actor as :retired in Datahike. Does not delete the row —
   the audit trail (and any historical messages tied to this actor)
   stay intact. Presence (room membership) is NOT touched; pair this
   with `dvergr.orchestration.daemon/stop-agent!` if you also want to tear down the
   live Participant (have it leave its rooms)."
  [conn id]
  (when (lookup conn id)
    (dh/transact conn [{:actor/id     id
                        :actor/status :retired}])
    (tel/log! {:id :actors/dismissed :data {:id id}} "Actor dismissed")
    :dismissed))

(defn update-actor!
  "Patch fields on an existing actor row. Pass any subset of the
   :name :skills :profile-ref :status :cost :config :external-refs
   fields. Skill sets replace (cardinality/many handles add/retract on
   reset). For partial skill updates use add-skill! / remove-skill!."
  [conn id patch]
  (let [existing (lookup conn id)]
    (when existing
      (let [merged (merge existing (assoc patch :id id))
            tx     (actor->tx merged)]
        (dh/transact conn [tx])
        (lookup conn id)))))

(defn add-skill!
  "Add a single skill to an existing actor."
  [conn id skill]
  (dh/transact conn [[:db/add [:actor/id id] :actor/skills skill]])
  (lookup conn id))

(defn remove-skill!
  "Remove a single skill from an existing actor."
  [conn id skill]
  (dh/transact conn [[:db/retract [:actor/id id] :actor/skills skill]])
  (lookup conn id))

;; ============================================================================
;; Boot-time fixture loading
;; ============================================================================

(defn- agent-config->actor-opts
  "Map a config.local.edn :agents entry to spawn-agent! opts."
  [[agent-id agent-config]]
  {:id          agent-id
   :name        (or (:name agent-config) (clojure.core/name agent-id))
   :profile-ref (some-> (or (:profile agent-config) agent-id) clojure.core/name (str ".md"))
   :skills      (set (or (:skills agent-config) (:tags agent-config) #{}))
   :config      (-> agent-config
                    (select-keys [:provider :model :budget-dollars
                                  :rooms :tools :description]))
   :external-refs (or (:external-refs agent-config) {})
   :status      :online})

(defn ensure-agent!
  "Idempotently materialize an :agent actor row for `id` from a
   config.local.edn-style `config` map. If the row already exists it is
   left untouched (runtime may have edited it since boot); returns the
   existing or freshly-created actor map.

   Called by `dvergr.orchestration.daemon/create-agent!` for every agent it
   brings online (config-declared, file-autostarted, or runtime-created) so the
   actors ⋈ membership view sees every hosted agent — closing the gap where
   dynamically-created agents used to exist only in the runtime registry."
  [conn id config]
  (or (lookup conn id)
      (spawn-agent! conn (agent-config->actor-opts [id config]))))

