(ns dvergr.ops
  "Central operations specification — ONE data map of dvergr's surface operations
   (rooms, agents, forks), from which the bindings are DERIVED rather than
   hand-rolled. Modeled on `datahike.api.specification`: the spec is data, and
   `spec->…` projections render it onto a binding (MCP today; the TUI/web/Telegram
   field-specs, command grammars, and form-parsers over time).

   Each entry is `{:doc :kind :schema :impl}`:
     :kind   — :read (a resource — what a UI renders) or :write (a mutation/tool)
     :schema — MALLI schema for the args (a `[:map …]`). The single source: MCP
               projects it to a JSON-Schema inputSchema via `malli.json-schema`
               (`input-schema`), and the HTTP/JSON API (`dvergr.web.api`) uses it
               directly for request coercion + the OpenAPI doc.
     :impl   — `(fn [daemon args] -> data)`. It binds the daemon execution
               context, resolves handles (room id/slug → Room), calls the
               ALREADY-shared op (`dvergr.agent.ops` / `dvergr.rooms` /
               `dvergr.rooms.forks` / `dvergr.discourse`), and returns plain data.
               The shared fns stay the logic; this is the normalized adapter every
               surface shares — so a new op is added once and appears, named
               identically, everywhere.

   Coding tools (`clojure_eval`, `knowledge_*`, file tools, …) are NOT specified
   here — they are the `dvergr.tools` registry, re-served by their own names.

   Op key → binding name: `:room/post` → `room_post` (MCP tool), etc."
  (:require [clojure.string :as str]
            [malli.json-schema :as mjs]
            [dvergr.agent.ops :as aops]
            [dvergr.agent.fields :as fields]
            [dvergr.rooms :as rooms]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.stats :as stats]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.discourse :as d]
            [dvergr.model.registry :as reg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

;; ============================================================================
;; Context + handle resolution
;; ============================================================================

(defn- slugify [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" "")))

(defn- dctx [daemon] (:execution-ctx daemon))

(defmacro ^:private in-ctx [daemon & body]
  `(binding [ec/*execution-context* (dctx ~daemon)] ~@body))

(defn- id->str
  "Room/actor ids are keywords (built-ins) OR UUIDs (created rooms) — both → string."
  [id]
  (cond (keyword? id) (name id) (some? id) (str id) :else nil))

(defn resolve-room
  "`room` arg → a live Room. Accepts a Room, a room-id (keyword/UUID/string), or a slug.
   Public so bindings (e.g. the MCP resource-subscription layer) can resolve handles."
  [daemon room]
  (in-ctx daemon
          (cond
            (or (nil? room) (record? room)) room
            :else
            (or (rreg/lookup room)                         ; the value as-is (keyword/UUID)
                (when (string? room)
                  (or (rreg/lookup (keyword room))
                      (try (rreg/lookup (java.util.UUID/fromString room)) (catch Throwable _ nil))
                      (some-> (rstore/slug->room-id room) rreg/lookup)))))))

;; ---- plain-data projections (serializable for any binding) ----

(defn- room-data [r]
  (when r
    {:id           (id->str (:id r))
     :title        (or (some-> r :meta deref :title) (id->str (:id r)))
     :parent       (id->str (some-> r :meta deref :conversation-id))
     :participants (try (mapv name (keys @(:participants r))) (catch Throwable _ []))}))

(defn- room-result
  "Project a Room OR a room-id (what create-room!/fork! return) to room-data."
  [daemon x]
  (in-ctx daemon (room-data (if (record? x) x (rreg/lookup x)))))

(defn- msg-data [m]
  {:from    (some-> (:from m) name)
   :to      (some-> (:to m) name)
   :role    (:role m)
   :ts      (:ts m)
   :content (:content m)})

;; ============================================================================
;; Reusable malli arg fragments — referenced from the specification below.
;; ============================================================================

(def ^:private Room    [:string {:description "room id or slug"}])
(def ^:private Fork    [:string {:description "fork room id/slug"}])
(def ^:private AgentId [:string {:description "agent id"}])

;; ============================================================================
;; The specification
;; ============================================================================

(def specification
  {;; ---- reads (resources) ----
   :room/list
   {:doc "List all rooms (id, title, participants)."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _] (in-ctx daemon (mapv room-data (rreg/list-rooms))))}

   :room/messages
   {:doc "Recent messages in a room — the conversation history."
    :kind :read
    :schema [:map [:room Room]
             [:limit {:optional true} [:int {:description "max messages (default 50)"}]]]
    :impl (fn [daemon {:keys [room limit]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (mapv msg-data (d/messages r {:limit (or limit 50)})))))}

   :agent/list
   {:doc "List all agents (durable — includes offline), with model/provider/status."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _] (in-ctx daemon (vec (aops/list-agents))))}

   :agent/config
   {:doc "An agent's full configuration (model, provider, tags, prompt source)."
    :kind :read
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}] (in-ctx daemon (aops/get-agent (keyword id))))}

   :room/stats
   {:doc "A room's stats — message count, cost, agents, fork count, last-active."
    :kind :read
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (stats/room-stats r))))}

   :room/detail
   {:doc "A room's identity + lineage — title, parent, fork status, participants."
    :kind :read
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      {:id           (id->str (:id r))
                       :slug         (:slug r)
                       :title        (or (:title r) (name (:id r)))
                       :parent-id    (some-> (:parent-id r) id->str)
                       :fork?        (forks/fork? r)
                       :forked-from  (some-> (:meta r) deref :forked-from id->str)
                       :participants (->> (some-> (:participants r) deref keys)
                                          (remove #(str/starts-with? (name %) "_"))
                                          (mapv id->str))})))}

   :room/diff
   {:doc "The diff of a fork against its parent (what reconcile-merge would apply)."
    :kind :read
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (forks/fork-diff r))))}

   :models/list
   {:doc "Available LLM models (for agent configuration): id, name, provider, context."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _]
            (in-ctx daemon
                    (mapv #(select-keys % [:id :name :provider :context]) (reg/list-models))))}

   :system/stats
   {:doc "Whole-system rollup — room count, message count, agents online, total cost."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _]
            (in-ctx daemon (stats/system-stats)))}

   ;; ---- writes (tools) ----
   :room/post
   {:doc "Post a message into a room — participate in the chat. The room's agents react."
    :kind :write
    :schema [:map [:room Room]
             [:text [:string {:description "message text"}]]
             [:as {:optional true}
              [:string {:description "poster actor (binding-specific: :local, the tg-user, :mcp …); default :mcp"}]]]
    :impl (fn [daemon {:keys [room text as]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (d/post! r (d/message (or (some-> as keyword) :mcp)
                                            (d/room-target r) text nil {:role :user}))
                      {:posted true :room (id->str (:id r))})))}

   :room/create
   {:doc "Create a new room."
    :kind :write
    :schema [:map [:title :string]
             [:slug {:optional true} :string]
             [:agents {:optional true} [:vector {:description "agent ids to join"} :string]]]
    :impl (fn [daemon {:keys [title slug agents]}]
            (in-ctx daemon
                    (let [sl (or slug (slugify title))]
                ;; create-room! returns the room-id keyword; look the live Room up.
                      (rooms/create-room! (cond-> {:title title :slug sl}
                                            agents (assoc :agent-ids (mapv keyword agents))))
                      (room-data (rreg/lookup (keyword sl))))))}

   :room/delete
   {:doc "Delete a room."
    :kind :write
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (rooms/delete-room! r) {:deleted (id->str (:id r))})))}

   :room/fork
   {:doc "Fork a room for speculative work (O(1) copy-on-write); merge or discard later."
    :kind :write
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (room-result daemon (in-ctx daemon (forks/fork! r)))))}

   :room/merge
   {:doc "Reconcile-merge a fork back into its parent."
    :kind :write
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (forks/reconcile-merge! r) {:merged (id->str (:id r))})))}

   :room/discard
   {:doc "Discard a fork without merging."
    :kind :write
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (forks/discard! r) {:discarded (id->str (:id r))})))}

   :agent/create
   {:doc "Create an agent (provision a durable row + bring it online)."
    :kind :write
    :schema (fields/create-schema)
    :impl (fn [daemon {:keys [id] :as fields}]
            (let [id (some-> id str str/trim not-empty)]
              (if-not id
                {:error "id required"}
                (in-ctx daemon
                        ((requiring-resolve 'dvergr.orchestration.daemon/provision-agent!)
                         daemon (assoc fields :id (keyword id)))
                        {:created id}))))}

   :agent/update
   {:doc "Update an agent's config (partial patch: model/provider/tags/prompt/…)."
    :kind :write
    :schema [:map [:id AgentId] [:patch (fields/update-schema)]]
    :impl (fn [daemon {:keys [id patch]}]
            (in-ctx daemon (aops/update-agent! (keyword id) patch) {:updated id}))}

   :agent/open
   {:doc "Open (ensure) a direct-message room with an agent; returns the room."
    :kind :write
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}]
            (room-result daemon
                         (in-ctx daemon
                                 ((requiring-resolve 'dvergr.orchestration.daemon/ensure-agent-room!)
                                  daemon (keyword id)))))}

   :room/invite
   {:doc "Invite (join) an agent into a room."
    :kind :write
    :schema [:map [:room Room] [:agent AgentId]]
    :impl (fn [daemon {:keys [room agent]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (let [aid  (keyword agent)
                            cfg  ((requiring-resolve 'dvergr.orchestration.daemon/agent-join-config) daemon aid)]
                        (if-not cfg
                          {:error (str "Unknown agent: " agent)}
                          (let [safe ((requiring-resolve 'dvergr.orchestration.daemon/resolve-safe-config) cfg)]
                            ((requiring-resolve 'dvergr.orchestration.daemon/join-agent-to-room!) daemon safe r)
                            {:invited agent :room (id->str (:id r))}))))))}

   :agent/delete
   {:doc "Delete an agent — stop the live participant, then retract its row + persona."
    :kind :write
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}]
            (in-ctx daemon
              ;; Stop the running agent first (the runtime half), then delete the
              ;; durable half — the complete deletion every surface should do.
                    (try ((requiring-resolve 'dvergr.orchestration.daemon/stop-agent!) daemon (keyword id))
                         (catch Throwable _ nil))
                    (aops/delete-agent! (keyword id))
                    {:deleted id}))}})

;; ============================================================================
;; Derivation helpers
;; ============================================================================

(defn op->name
  "Op key → flat binding name: `:room/post` → \"room_post\"."
  [op]
  (str (namespace op) "_" (name op)))

(defn malli-schema
  "The malli args schema for op `op` (a `[:map …]`) — the source the HTTP/JSON
   API coerces against directly."
  [op]
  (:schema (get specification op)))

(defn input-schema
  "JSON Schema for op `op`'s args — the malli `:schema` projected via
   `malli.json-schema`, for the MCP inputSchema / OpenAPI."
  [op]
  (mjs/transform (malli-schema op)))

(defn reads  [] (into {} (filter (comp #(= :read  (:kind %)) val) specification)))
(defn writes [] (into {} (filter (comp #(= :write (:kind %)) val) specification)))

(defn invoke
  "Run op `op` (a spec key) with `args` (a keyword map) against `daemon`. Returns
   the op's data result, or throws. The single call-path every binding uses."
  [daemon op args]
  (if-let [{:keys [impl]} (get specification op)]
    (impl daemon (or args {}))
    (throw (ex-info (str "Unknown op: " op) {:op op}))))
