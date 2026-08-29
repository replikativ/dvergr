(ns dvergr.room.store.datahike
  "Datahike PRoomStore — wraps the existing :chat/* :room/* :message/*
   schema. Same backing data the old `dvergr.rooms` namespace read
   from; this just exposes it behind the unified protocol so Rooms
   can be persistence-agnostic.

   The store maps a Room's keyword id ↔ a Datahike :chat/id UUID via
   the :room/slug attribute (`slug->room-id`/`room-id->slug` in
   `dvergr.room.store`)."
  (:require [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.persist :as persist]
            [dvergr.room.store :as store]
            [taoensso.telemere :as tel]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- room-by-slug
  [conn slug]
  (dh/q '[:find (pull ?e [:chat/id :chat/title :chat/updated-at
                          :room/slug :room/type :room/parent-id
                          :room/telegram-chat-id :room/agent-ids]) .
          :in $ ?slug
          :where [?e :room/slug ?slug]]
        @conn slug))

(defn- room->metadata
  "Convert a Datahike room entity to the PRoomStore metadata shape."
  [ent]
  (when ent
    (cond-> {:id              (store/slug->room-id (:room/slug ent))
             :slug            (:room/slug ent)
             :title           (:chat/title ent)
             :chat-id         (:chat/id ent)
             :type            (or (:room/type ent) :internal)
             :updated-at      (:chat/updated-at ent)
             :agent-ids       (set (:room/agent-ids ent))}
      (:room/parent-id ent)        (assoc :parent-id (store/slug->room-id
                                                      (some-> (dh/q '[:find (pull ?p [:room/slug]) .
                                                                      :in $ ?pid
                                                                      :where [?p :chat/id ?pid]]
                                                                    @(:conn ent) (:room/parent-id ent))
                                                              :room/slug)))
      (:room/telegram-chat-id ent) (assoc :telegram-chat-id (:room/telegram-chat-id ent)))))

(defn- metadata->room-tx
  "Build a Datahike tx for a room metadata map."
  [conn {:keys [slug title chat-id type parent-id agent-ids telegram-chat-id]}]
  (let [chat-id (or chat-id
                    (some-> (room-by-slug conn slug) :chat/id)
                    (random-uuid))
        ;; Resolve the parent's chat-id IN THIS store. Per-room stores (RF5) hold
        ;; only their own room row, so a cross-room parent often isn't present —
        ;; only set :room/parent-id when it actually resolves (else datahike
        ;; rejects a nil value + spams the boot log).
        parent-chat-id (when parent-id
                         (some-> (room-by-slug conn (store/room-id->slug parent-id))
                                 :chat/id))
        base    (schema/create-chat-entity {:id chat-id :title (or title slug)})
        room    (cond-> (merge base
                               {:room/slug slug
                                :room/type (or type :internal)})
                  (seq agent-ids)
                  (assoc :room/agent-ids (set agent-ids))

                  telegram-chat-id
                  (assoc :room/telegram-chat-id (long telegram-chat-id))

                  parent-chat-id
                  (assoc :room/parent-id parent-chat-id))]
    [chat-id room]))

(defn- message->entity
  "Convert a discourse.Message (or message-shaped map) to a Datahike
   message entity tied to the given chat-id."
  [chat-id msg]
  (let [msg          (store/normalize-message-thread msg)
        content      (str (:content msg))
        msg-id       (:id msg)
        ts           (some-> (:ts msg) (java.util.Date.))
        metadata     (store/validate-message-metadata! (:metadata msg))
        attachment   (:attachment metadata)
        provenance   (:provenance metadata)
        object       (:object metadata)
        blob-id      (:blob-id attachment)
        role         (store/infer-role msg)
        source-user  (or (:source-user metadata)
                         (some-> (:from msg) name)
                         "unknown")]
    (cond-> {:message/id         msg-id
             :message/chat       [:chat/id chat-id]
             :message/role       role
             :message/content    content
             :message/created-at (or ts (java.util.Date.))
             :message/source-user source-user}
      (keyword? (:from msg)) (assoc :message/from (:from msg))
      (keyword? (:to msg)) (assoc :message/to (:to msg))
      (uuid? (:in-reply-to msg)) (assoc :message/in-reply-to (:in-reply-to msg))
      (uuid? (:thread-root-id msg)) (assoc :message/thread-root-id (:thread-root-id msg))
      (uuid? (:run-id metadata)) (assoc :message/run-id (:run-id metadata))
      (:source-username metadata) (assoc :message/source-username (:source-username metadata))
      (:source-user-id  metadata) (assoc :message/source-user-id  (long (:source-user-id metadata)))
      (seq (:audience metadata)) (assoc :message/audience (set (:audience metadata)))
      (seq (:mentions metadata)) (assoc :message/mention-handles
                                        (set (map str (:mentions metadata))))
      (:kind metadata) (assoc :message/metadata-kind (:kind metadata))
      (:from metadata) (assoc :message/context-from (:from metadata))
      (:source metadata) (assoc :message/source (:source metadata))
      (:schedule-id metadata) (assoc :message/schedule-id (:schedule-id metadata))
      (uuid? blob-id) (assoc :message/attachment-store-ref blob-id)
      (and blob-id (not (uuid? blob-id)))
      (assoc :message/attachment-blob-id (str blob-id))
      (:node-id attachment) (assoc :message/attachment-node-id
                                   (str (:node-id attachment)))
      (:mime attachment) (assoc :message/attachment-mime (:mime attachment))
      (:name attachment) (assoc :message/attachment-name (:name attachment))
      (:size attachment) (assoc :message/attachment-size (long (:size attachment)))
      (:mode provenance) (assoc :message/provenance-mode (:mode provenance))
      (:source provenance) (assoc :message/provenance-source (:source provenance))
      (:kind object) (assoc :message/object-kind (:kind object))
      (:id object) (assoc :message/object-id (:id object))
      (:notification/type metadata)
      (assoc :message/notification-type (:notification/type metadata))
      (:notification/agent metadata)
      (assoc :message/notification-agent (:notification/agent metadata))
      (:notification/task metadata)
      (assoc :message/notification-task (:notification/task metadata))
      (:notification/elapsed metadata)
      (assoc :message/notification-elapsed (long (:notification/elapsed metadata)))
      ;; Structured tool-uses (already serialized component maps from the
      ;; chat-ctx in-memory signal — :tool-use/id is plain string, not
      ;; unique, so re-transacting creates fresh components). This is how a
      ;; room captures an agent's tool activity at full fidelity, closing the
      ;; gap where the room-store path used to drop tool-uses the chat-ctx
      ;; path kept.
      (seq (:tool-uses metadata)) (assoc :message/tool-uses (vec (:tool-uses metadata)))
      ;; Interleaved-thinking trace from a reasoning-model reply, so it survives
      ;; rehydration and is fed back to the model (see room-context seeding).
      (seq (:reasoning metadata))  (assoc :message/reasoning (:reasoning metadata)))))

(def ^:private message-pull-pattern
  '[:message/id :message/role :message/content
    :message/created-at :message/source-user
    :message/source-username :message/source-user-id
    :message/from :message/to :message/in-reply-to
    :message/thread-root-id :message/run-id
    :message/reasoning
    :message/audience :message/mention-handles
    :message/metadata-kind :message/context-from
    :message/source :message/schedule-id
    :message/attachment-store-ref
    :message/attachment-blob-id
    :message/attachment-node-id
    :message/attachment-mime
    :message/attachment-name
    :message/attachment-size
    :message/provenance-mode
    :message/provenance-source
    :message/object-kind :message/object-id
    :message/notification-type
    :message/notification-agent
    :message/notification-task
    :message/notification-elapsed
    {:message/tool-uses
     [:tool-use/id :tool-use/name
      {:tool-use/input [*]}]}])

(def ^:private run-pull-pattern
  '[:run/id :run/kind :run/room :run/actor :run/trigger :run/parent
    :run/roster :run/agent-version :run/program-kind :run/interpreter-version
    :run/agent-def-hash :run/chat-id
    :run/world :run/isolation :run/settlement-policy
    :run/settlement-status :run/settlement-reason
    :run/status :run/created-at :run/started-at :run/updated-at :run/ended-at
    :run/reason :run/error])

(defn- run->entity [chat-id run]
  (cond-> {:run/id         (:run/id run)
           :run/chat       [:chat/id chat-id]
           :run/kind       (:run/kind run)
           :run/room       (:run/room run)
           :run/actor      (:run/actor run)
           :run/trigger    (:run/trigger run)
           :run/status     (:run/status run)
           :run/created-at (:run/created-at run)
           :run/started-at (:run/started-at run)
           :run/updated-at (:run/updated-at run)}
    (:run/parent run)   (assoc :run/parent (:run/parent run))
    (:run/roster run) (assoc :run/roster (:run/roster run))
    (:run/agent-version run) (assoc :run/agent-version (:run/agent-version run))
    (:run/program-kind run) (assoc :run/program-kind (:run/program-kind run))
    (:run/interpreter-version run)
    (assoc :run/interpreter-version (:run/interpreter-version run))
    (:run/agent-def-hash run) (assoc :run/agent-def-hash (:run/agent-def-hash run))
    (:run/chat-id run) (assoc :run/chat-id (:run/chat-id run))
    (:run/world run) (assoc :run/world (:run/world run))
    (:run/isolation run) (assoc :run/isolation (:run/isolation run))
    (:run/settlement-policy run)
    (assoc :run/settlement-policy (:run/settlement-policy run))
    (:run/settlement-status run)
    (assoc :run/settlement-status (:run/settlement-status run))
    (:run/settlement-reason run)
    (assoc :run/settlement-reason (:run/settlement-reason run))
    (:run/ended-at run) (assoc :run/ended-at (:run/ended-at run))
    (:run/reason run)   (assoc :run/reason (:run/reason run))
    (:run/error run)    (assoc :run/error (str (:run/error run)))))

;; =============================================================================
;; Store impl
;; =============================================================================

(defrecord DatahikeStore [conn]
  store/PRoomStore

  (-store-room! [_ room-id metadata]
    (let [slug    (or (:slug metadata) (store/room-id->slug room-id))
          [_ tx] (metadata->room-tx conn (assoc metadata :slug slug))]
      (try
        (dh/transact conn [tx
                           {:db/id [:chat/id (:chat/id tx)]
                            :chat/updated-at (java.util.Date.)}])
        (catch Throwable t
          (tel/log! {:level :warn :id :room-store/datahike-store-room-failed
                     :data {:room-id room-id :error (.getMessage t)}})))))

  (-load-room [_ id-or-slug]
    (let [slug (cond
                 (keyword? id-or-slug) (store/room-id->slug id-or-slug)
                 :else                 (str id-or-slug))
          ent  (room-by-slug conn slug)]
      (when ent (assoc (room->metadata (assoc ent :conn conn))
                       :conn nil))))

  (-delete-room! [_ room-id]
    (let [slug (store/room-id->slug room-id)]
      (when-let [ent (room-by-slug conn slug)]
        (let [chat-id (:chat/id ent)
              msg-ids (dh/q '[:find [?mid ...]
                              :in $ ?cid
                              :where [?c :chat/id ?cid]
                              [?m :message/chat ?c]
                              [?m :message/id ?mid]]
                            @conn chat-id)
              run-ids (dh/q '[:find [?rid ...]
                              :in $ ?cid
                              :where
                              [?c :chat/id ?cid]
                              [?r :run/chat ?c]
                              [?r :run/id ?rid]]
                            @conn chat-id)]
          (dh/transact conn (-> (mapv (fn [mid] [:db/retractEntity [:message/id mid]]) msg-ids)
                                (into (map (fn [rid] [:db/retractEntity [:run/id rid]]) run-ids))
                                (conj [:db/retractEntity [:chat/id chat-id]])))))))

  (-list-rooms [_]
    (->> (dh/q '[:find [(pull ?e [:chat/id :chat/title :chat/updated-at
                                  :room/slug :room/type :room/parent-id
                                  :room/telegram-chat-id :room/agent-ids]) ...]
                 :where [?e :room/slug _]]
               @conn)
         (mapv (fn [ent] (assoc (room->metadata (assoc ent :conn conn))
                                :conn nil)))
         (sort-by #(- (.getTime (or (:updated-at %) (java.util.Date. 0)))))
         vec))

  (-store-message! [_ room-id msg]
    (let [slug (store/room-id->slug room-id)]
      (if-let [ent (room-by-slug conn slug)]
        ;; PRoomStore promises first-write-wins idempotence. A lookup-identity
        ;; upsert alone would silently overwrite content when an adapter retries
        ;; the same id with a changed envelope.
        (when-not (dh/q '[:find ?m .
                          :in $ ?mid
                          :where [?m :message/id ?mid]]
                        @conn (:id msg))
          (let [chat-id (:chat/id ent)
                entity  (message->entity chat-id msg)]
            ;; One durability policy (surface + retry-once + dead-letter) instead
            ;; of the old catch-and-silently-drop — a lost message is now visible
            ;; and recoverable, not swallowed at :warn.
            (persist/persist-tx! conn
                                 [entity
                                  {:db/id [:chat/id chat-id]
                                   :chat/updated-at (java.util.Date.)}]
                                 {:op :store-message :room-id room-id :msg-id (:id msg)})))
        (tel/log! {:level :error :id :room-store/datahike-missing-room
                   :data {:room-id room-id :msg-id (:id msg)}}
                  "message for unknown room — not persisted (dropped)"))))

  (-message-thread-root [_ room-id message-id]
    (let [slug (store/room-id->slug room-id)]
      (when-let [room (room-by-slug conn slug)]
        (let [message (dh/q '[:find (pull ?m [:message/id :message/in-reply-to
                                              :message/thread-root-id]) .
                              :in $ ?cid ?mid
                              :where
                              [?c :chat/id ?cid]
                              [?m :message/chat ?c]
                              [?m :message/id ?mid]]
                            @conn (:chat/id room) message-id)]
          ;; Legacy rows predate the typed root. Immediate parent is the best
          ;; bounded compatibility root; new nested replies always have the
          ;; explicit ancestor root.
          (or (:message/thread-root-id message)
              (:message/in-reply-to message)
              (:message/id message))))))

  (-list-messages [_ room-id {:keys [limit since thread-root-id]}]
    (let [slug (store/room-id->slug room-id)]
      (when-let [ent (room-by-slug conn slug)]
        (let [chat-id (:chat/id ent)
              ;; Resolve matching entity ids in Datalog before pulling message
              ;; bodies. In particular, the indexed thread root now bounds the
              ;; amount of data crossing the store boundary instead of loading
              ;; the whole Room and filtering it in Clojure. The two legacy
              ;; branches retain compatibility only for rows written before the
              ;; typed root existed.
              message-ids
              (if thread-root-id
                (dh/q '[:find [?m ...]
                        :in $ ?cid ?root
                        :where
                        [?c :chat/id ?cid]
                        [?m :message/chat ?c]
                        (or-join [?m ?root]
                                 [?m :message/thread-root-id ?root]
                                 (and [?m :message/id ?root]
                                      (not [?m :message/thread-root-id _]))
                                 (and [?m :message/in-reply-to ?root]
                                      (not [?m :message/thread-root-id _])))]
                      @conn chat-id thread-root-id)
                (dh/q '[:find [?m ...]
                        :in $ ?cid
                        :where
                        [?c :chat/id ?cid]
                        [?m :message/chat ?c]]
                      @conn chat-id))
              base    (dh/pull-many @conn message-pull-pattern message-ids)
              sorted  (sort-by #(.getTime (or (:message/created-at %)
                                              (java.util.Date. 0))) base)
              filtered (if since
                         (filter #(when-let [t (:message/created-at %)]
                                    (> (.getTime ^java.util.Date t)
                                       (.getTime ^java.util.Date since)))
                                 sorted)
                         sorted)]
          ;; Normalize to unified Message shape — consumers (TUI, sandbox)
          ;; see {:id :from :to :content :ts :role :metadata} regardless of
          ;; which store backs the room.
          (mapv (fn [m]
                  (let [attachment (cond-> {}
                                     (or (:message/attachment-store-ref m)
                                         (:message/attachment-blob-id m))
                                     (assoc :blob-id
                                            (or (:message/attachment-store-ref m)
                                                (:message/attachment-blob-id m)))
                                     (:message/attachment-node-id m)
                                     (assoc :node-id (:message/attachment-node-id m))
                                     (:message/attachment-mime m)
                                     (assoc :mime (:message/attachment-mime m))
                                     (:message/attachment-name m)
                                     (assoc :name (:message/attachment-name m))
                                     (:message/attachment-size m)
                                     (assoc :size (:message/attachment-size m)))
                        provenance (cond-> {}
                                     (:message/provenance-mode m)
                                     (assoc :mode (:message/provenance-mode m))
                                     (:message/provenance-source m)
                                     (assoc :source (:message/provenance-source m)))
                        object (cond-> {}
                                 (:message/object-kind m)
                                 (assoc :kind (:message/object-kind m))
                                 (:message/object-id m)
                                 (assoc :id (:message/object-id m)))
                        metadata (cond-> {:role (:message/role m)}
                                   (:message/source-user m)
                                   (assoc :source-user (:message/source-user m))
                                   (:message/source-username m)
                                   (assoc :source-username (:message/source-username m))
                                   (:message/source-user-id m)
                                   (assoc :source-user-id (:message/source-user-id m))
                                   (seq (:message/audience m))
                                   (assoc :audience (set (:message/audience m)))
                                   (seq (:message/mention-handles m))
                                   (assoc :mentions (set (:message/mention-handles m)))
                                   (seq attachment) (assoc :attachment attachment)
                                   (seq provenance) (assoc :provenance provenance)
                                   (seq object) (assoc :object object)
                                   (:message/metadata-kind m)
                                   (assoc :kind (:message/metadata-kind m))
                                   (:message/context-from m)
                                   (assoc :from (:message/context-from m))
                                   (:message/source m)
                                   (assoc :source (:message/source m))
                                   (:message/schedule-id m)
                                   (assoc :schedule-id (:message/schedule-id m))
                                   (:message/notification-type m)
                                   (assoc :notification/type
                                          (:message/notification-type m))
                                   (:message/notification-agent m)
                                   (assoc :notification/agent
                                          (:message/notification-agent m))
                                   (:message/notification-task m)
                                   (assoc :notification/task
                                          (:message/notification-task m))
                                   (:message/notification-elapsed m)
                                   (assoc :notification/elapsed
                                          (:message/notification-elapsed m))
                                   (seq (:message/tool-uses m))
                                   (assoc :tool-uses (:message/tool-uses m))
                                   (seq (:message/reasoning m))
                                   (assoc :reasoning (:message/reasoning m)))]
                    (store/normalize-message-thread
                     (cond-> {:id        (:message/id m)
                              :from      (or (:message/from m)
                                             (some-> (:message/source-user m) keyword))
                              :to        (:message/to m)
                              :content   (:message/content m)
                              :ts        (some-> (:message/created-at m) .getTime)
                              :role      (:message/role m)
                              :metadata  metadata}
                       (:message/in-reply-to m)
                       (assoc :in-reply-to (:message/in-reply-to m))
                       (:message/thread-root-id m)
                       (assoc :thread-root-id (:message/thread-root-id m))
                    ;; Surface structured tool-uses so rich frontends render an
                    ;; agent's tool activity inline (same as the chat-ctx view).
                       (seq (:message/tool-uses m))
                       (assoc :tool-uses (:message/tool-uses m))
                    ;; Surface the interleaved-thinking trace so seeding can feed
                    ;; it back to reasoning models (MiniMax M2 / Kimi / DeepSeek).
                       (seq (:message/reasoning m))
                       (assoc :reasoning (:message/reasoning m))
                       (:message/run-id m)
                       (assoc-in [:metadata :run-id] (:message/run-id m))))))
                (vec (take-last (or limit 100) filtered)))))))

  (-store-run! [this room-id run]
    (let [slug (store/room-id->slug room-id)]
      (when-let [ent (room-by-slug conn slug)]
        (let [run (->> run
                       store/validate-run!
                       (store/validate-run-update!
                        (store/-load-run this room-id (:run/id run))))]
          (when (persist/persist-tx!
                 conn
                 [(run->entity (:chat/id ent) run)
                  {:db/id [:chat/id (:chat/id ent)]
                   :chat/updated-at (java.util.Date.)}]
                 {:op :store-run :room-id room-id :run-id (:run/id run)})
            run)))))

  (-load-run [_ room-id run-id]
    (let [slug (store/room-id->slug room-id)]
      (when-let [ent (room-by-slug conn slug)]
        (dh/q '[:find (pull ?r pattern) .
                :in $ ?chat-id ?run-id pattern
                :where
                [?c :chat/id ?chat-id]
                [?r :run/chat ?c]
                [?r :run/id ?run-id]]
              @conn (:chat/id ent) run-id run-pull-pattern))))

  (-list-runs [_ room-id {:keys [limit status actor]}]
    (let [slug (store/room-id->slug room-id)]
      (if-let [ent (room-by-slug conn slug)]
        (->> (dh/q '[:find [(pull ?r pattern) ...]
                     :in $ ?chat-id pattern
                     :where
                     [?c :chat/id ?chat-id]
                     [?r :run/chat ?c]]
                   @conn (:chat/id ent) run-pull-pattern)
             (filter #(if status (= status (:run/status %)) true))
             (filter #(if actor (= actor (:run/actor %)) true))
             (sort-by (juxt #(some-> ^java.util.Date (:run/started-at %) .getTime)
                            #(str (:run/id %)))
                      #(compare %2 %1))
             (take (or limit 100))
             vec)
        []))))

(defn make
  "Create a DatahikeStore. `conn` must be an existing Datahike
   connection whose db includes the dvergr.chat.schema attributes."
  [conn]
  (->DatahikeStore conn))
