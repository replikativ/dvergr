(ns dvergr.discourse
  "Durable envelopes, retained projections and logical decision bindings."
  (:require [dvergr.room :as room]
            [dvergr.schema :as schema]
            [dvergr.store :as store]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.delayed :as clock]
            [org.replikativ.spindel.pubsub.buffer :as buffer]
            [org.replikativ.spindel.pubsub.mult :as mult]
            [org.replikativ.spindel.spin.combinators :as timers]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.value :as v]
            [dvergr.world.systems :as systems]
            [yggdrasil.protocols :as p]))

(deftype ^:private Author [installation room world principal participant observe edit])
(alter-meta! #'->Author assoc :private true)

(defn ^:no-doc authenticate!
  "Trusted host identity adapter seam; never an SCI export."
  [installation live-room {:keys [principal-id participant-id observe-addressed? edit-decisions?]}]
  (room/open! installation live-room)
  (v/identity! :participant participant-id)
  (v/identity! :participant principal-id)
  (Author. (:id installation) (get-in live-room [:record :room/id])
           (get-in live-room [:record :room/world]) principal-id participant-id
           (true? observe-addressed?) (true? edit-decisions?)))

(defn- identity-data! [installation venue author local?]
  (room/open! installation (:room venue))
  (when-not (instance? Author author) (v/fail! :operation-denied {}))
  (let [^Author a author
        selected (systems/selected! installation (.-world a))]
    (when-not (and (= (:id installation) (.-installation a))
                   (= (get-in venue [:room :record :room/id]) (.-room a)))
      (v/fail! :operation-denied {}))
    (when (and local? (not= (get-in selected [:descriptor :world/id]) (.-world a)))
      (v/fail! :effect-boundary-unavailable {}))
    {:principal/id (.-principal a) :participant/id (.-participant a)}))

(defn ^:no-doc author! [installation venue author]
  (identity-data! installation venue author true))

(defn- editor! [installation venue author]
  (identity-data! installation venue author false)
  (when-not (.-edit ^Author author) (v/fail! :operation-denied {})))

(defn- basis [installation venue]
  (p/snapshot-id (systems/resolve! installation (:system venue) :discourse/read)))

(defn ^:no-doc persist! [installation venue author-data spec]
  (schema/message! spec)
  (room/open! installation (:room venue))
  (room/work! installation venue :discourse/write
              {:command :post :id (:id spec)
               :origin (get-in (systems/selected! installation
                                                  (:anchor (systems/handle-data (:system venue))))
                               [:descriptor :world/id])
               :canonical {:request spec :author author-data
                           :room/id (get-in venue [:room :record :room/id])
                           :projection/id (:projection/id venue)}}))

(defn ^:no-doc offer! [installation venue result]
  (if (= :inserted (:persistence result))
    (try
      (sync/post! (get-in venue [:room :mailbox]) (:envelope result))
      (assoc result :live-publication :offered)
      (catch Throwable _ result)) result))

(defn post!
  "Persist before best-effort publication; existing identities never replay."
  [installation venue author spec]
  (locking (:lock installation)
    (let [identity (author! installation venue author)]
      (when (contains? spec :destination) (v/fail! :effect-boundary-unavailable {}))
      (offer! installation venue (persist! installation venue identity spec)))))

(defn history
  "Read a bounded page at one retained basis; cursor replay is explicit."
  [installation venue {:keys [limit cursor] :or {limit 100} :as query}]
  (schema/request! query)
  (when-not (and (every? #{:limit :cursor} (keys query)) (int? limit) (<= 1 limit 256))
    (v/fail! :invalid-request {}))
  (locking (:lock installation)
    (room/open! installation (:room venue))
    (when (and cursor
               (not (and (map? cursor) (= #{:basis :projection/id :after} (set (keys cursor)))
                         (string? (:basis cursor)) (string? (:after cursor))
                         (= (:projection/id venue) (:projection/id cursor)))))
      (v/fail! :invalid-cursor {}))
    (room/work! installation venue :discourse/read
                {:command :history :id (:projection/id venue)
                 :basis (:basis cursor) :after (:after cursor) :limit limit})))

(defn branch!
  "Retain a parent history basis and share the exact execution world."
  [installation venue {:keys [id] :as spec}]
  (when-not (= #{:id} (set (keys spec))) (v/fail! :invalid-request {}))
  (v/identity! :projection id)
  (locking (:lock installation)
    (room/open! installation (:room venue))
    (let [old (room/work! installation venue :discourse/read {:command :projection :id id})
          record (or old {:projection/id id :projection/parent (:projection/id venue)
                          :projection/basis (basis installation venue)
                          :room/id (get-in venue [:room :record :room/id])})]
      (when-not (and (= (:projection/id venue) (:projection/parent record))
                     (= (get-in venue [:room :record :room/id]) (:room/id record)))
        (v/fail! :identity-conflict {}))
      (room/work! installation venue :discourse/write {:command :projection :record record})
      (room/venue installation (:room venue) id))))

(defn ^:no-doc decision-binding! [installation venue decision-id]
  (let [data (systems/handle-data (:system venue))
        room (:room venue)
        binding (or (room/work! installation venue :discourse/read {:command :decision :id decision-id})
                    (v/fail! :unknown-decision {}))]
    (when-not (and (= (:projection/id venue) (:projection/id binding))
                   (or (nil? room)
                       (and (= (:anchor data) (get-in room [:record :room/world]))
                            (= (:system-id data) (get-in room [:record :room/discourse :system])))))
      (v/fail! :operation-denied {}))
    binding))

(defn decision-handle
  "Build a logical decision reference using the existing system handle."
  [installation venue decision-id]
  (v/identity! :decision decision-id)
  (decision-binding! installation venue decision-id)
  {:decision/id decision-id :system (:system venue) :projection/id (:projection/id venue)})

(defn set-decider!
  "Replace one work binding at its expected version."
  [installation handle author {:keys [expected-version participant-id] :as request}]
  (locking (:lock installation)
    (let [r (some (fn [[_ owners]] (some #(when (= (:room/world (:record %))
                                                   (:anchor (systems/handle-data (:system handle)))) %) owners))
                  @(:owners installation))
          venue (assoc handle :room r)]
      (editor! installation venue author)
      (decision-binding! installation venue (:decision/id handle))
      (when-not (= #{:expected-version :participant-id} (set (keys request))) (v/fail! :invalid-request {}))
      (room/work! installation venue :discourse/write {:command :decider :id (:decision/id handle)
                                                       :expected-version expected-version :participant-id participant-id}))))

(defn ^:no-doc selector! [selector]
  (schema/request! selector)
  (when-not (and (map? selector) (every? #{:tags :direct? :broadcast?} (keys selector))
                 (or (nil? (:tags selector))
                     (and (set? (:tags selector)) (every? keyword? (:tags selector))))
                 (every? #(or (nil? (get selector %)) (boolean? (get selector %))) [:direct? :broadcast?]))
    (v/fail! :invalid-selector {}))
  selector)

(defn ^:no-doc matches? [venue identity observe? selector envelope]
  (let [broadcast? (= :broadcast (:recipients envelope))
        direct? (contains? (get-in envelope [:recipients :participants] #{}) (:participant/id identity))]
    (and (= (:projection/id venue) (:projection/id envelope))
         (or broadcast? direct? observe?)
         (or (and broadcast? (:broadcast? selector)) (and direct? (:direct? selector))
             (some (:tags selector) (:tags envelope))))))

(defn observe!
  "Install a raw tap; history remains the completeness mechanism."
  [installation venue author selector]
  (locking (:lock installation)
    (let [identity (author! installation venue author) _ (selector! selector)
          r (:room venue) id (random-uuid)
          tap (mult/tap (:mult r) (buffer/sliding-buffer 32))
          done (sync/create-deferred (:context (:world r))) closed (atom false) close-lock (Object.)
          close (fn []
                  (binding [ec/*execution-context* (:context (:world r))]
                    (locking close-lock
                      (when-not @closed
                        (mult/untap (:mult r) tap)
                        (sync/deliver! done {:status :closed})
                        (reset! closed true)
                        (swap! (get-in r [:owner :observers]) dissoc id)))) done)
          stream (reify aseq/PAsyncSeq
                   (anext [this]
                     (spin (loop []
                             (when-not @closed
                               (when-let [[e _] (await (aseq/anext tap))]
                                 (if (matches? venue identity (.-observe ^Author author) selector e)
                                   [e this] (recur))))))))]
      (swap! (get-in r [:owner :observers]) assoc id close)
      {:installation installation :room r :stream stream :close close})))

(defn unobserve!
  "Detach an owned raw observation idempotently, including from a host thread."
  [installation observation]
  (when-not (identical? installation (:installation observation)) (v/fail! :operation-denied {}))
  ((:close observation)))

(deftype ^:private AskOwner [installation room id token])
(alter-meta! #'->AskOwner assoc :private true)

(defn resolution
  "Read terminal control evidence without recreating execution."
  [control id]
  (or (:ask/terminal (or (store/ask-record control id) (v/fail! :unknown-ask {}))) {:status :pending}))

(defn ^:no-doc finish!
  ([installation r id candidate] (finish! installation r id candidate false))
  ([installation r id candidate from-timer?]
   (let [[winner entry] (locking (:lock installation)
                          [(store/terminal! (:control installation) id candidate)
                           (get @(get-in r [:owner :asks]) id)])]
     ;; Keep registration retryable until both native operations succeed.
     (when entry
       (binding [ec/*execution-context* (:context (:world r))]
         (sync/deliver! (:result entry) winner)
         (when-not from-timer? (room/cancel-task! installation r @(:timer entry))))
       (locking (:lock installation)
         (when (identical? entry (get @(get-in r [:owner :asks]) id))
           (swap! (get-in r [:owner :asks]) dissoc id))))
     winner)))

(defn ^:no-doc request-eligible?
  "Fence request delivery to its selected incarnation; replies remain ordinary envelopes."
  [installation session-id envelope]
  (if-let [id (:ask/id envelope)]
    (let [intent (:ask/intent (store/ask-record (:control installation) id))]
      (or (not= (:message/id envelope) (:message/id intent))
          (and (some? session-id) (= session-id (:session intent)))))
    true))

(defn ^:no-doc ask-selected!
  "Register intent before request commit; start execution outside the lifecycle lock."
  [installation venue author {:keys [id message deadline target] :as request} decision]
  (schema/request! request)
  (when-not (= #{:id :message :deadline :target} (set (keys request))) (v/fail! :invalid-request {}))
  (v/identity! :ask id) (v/identity! :participant target)
  (when-not (pos-int? deadline) (v/fail! :invalid-deadline {}))
  (schema/message! message)
  (when-not (= {:participants #{target}} (:recipients message)) (v/fail! :invalid-request {}))
  (let [r (:room venue) owner (:owner r)
        [entry launch ownership] (locking (:lock installation)
                                   (let [identity (author! installation venue author)
                                         old (store/ask-record (:control installation) id)
                                         session (get @(:sessions owner) [(:projection/id venue) target])
                                         intent {:fingerprint (v/fingerprint message) :message/id (:id message) :caller identity :target target
                                                 :session (if old (get-in old [:ask/intent :session]) (:id session))
                                                 :deadline deadline :room/id (get-in r [:record :room/id])
                                                 :projection/id (:projection/id venue) :world/id (get-in r [:record :room/world])
                                                 :process (if old (get-in old [:ask/intent :process]) (:process installation))
                                                 :policy 1 :decision decision}
                                         _ (store/put-ask! (:control installation) id intent)
                                         active (get @(:asks owner) id)
                                         entry (or active {:result (sync/create-deferred (:context (:world r)))
                                                           :token (Object.) :timer (atom nil) :intent intent
                                                           :finish #(finish! installation r id %)})
                                         task (when-not active
                                                (spin
                                                 (await (timers/sleep (max 0 (- deadline (clock/current-time (:context (:world r)))))))
                                                 (loop []
                                                   (let [finished? (try (finish! installation r id {:status :timeout} true) true
                                                                        (catch Throwable _ false))]
                                                     (when-not finished?
                                                       (await (timers/sleep 10)) (recur))))))]
                                     (when task (reset! (:timer entry) task))
                                     (swap! (:asks owner) assoc id entry)
                                     [entry (when task (room/prepare! installation r task (fn [_]) (fn [_])))
                                      (AskOwner. installation r id (:token entry))]))]
    (when launch (launch))
    (let [posted (locking (:lock installation)
                   (let [posted (persist! installation venue (get-in entry [:intent :caller]) (assoc message :ask/id id))
                         session (get @(:sessions owner) [(:projection/id venue) target])]
                     (if (and session (not @(:closed session))
                              (request-eligible? installation (:id session) (:envelope posted)))
                       (offer! installation venue posted) posted)))
          record (store/ask-record (:control installation) id)]
      (cond (:ask/terminal record) (finish! installation r id (:ask/terminal record))
            (nil? (get-in entry [:intent :session])) (finish! installation r id {:status :leave :reason :missing-participant}))
      {:id id :result (:result entry) :owner ownership :post posted})))

(defn ask
  "Ask one selected local session with first-terminal-control-commit arbitration."
  [installation venue author request]
  (ask-selected! installation venue author request nil))

(defn cancel!
  "Use cancellation ownership from any host thread; never replace a winner."
  [installation ownership reason]
  (schema/request! reason)
  (when-not (and (instance? AskOwner ownership)
                 (identical? installation (.-installation ^AskOwner ownership)))
    (v/fail! :operation-denied {}))
  (let [^AskOwner o ownership r (.-room o) entry (get @(get-in r [:owner :asks]) (.-id o))]
    (when (and entry (not (identical? (:token entry) (.-token o)))
               (nil? (:ask/terminal (store/ask-record (:control installation) (.-id o)))))
      (v/fail! :operation-denied {}))
    (finish! installation r (.-id o) {:status :cancelled :reason reason})))

(defn recover!
  "Reconcile a bounded batch, notifying live registrations through normal cleanup."
  [installation r]
  (let [record (if (:record r) (:record r) r)
        live (when (:owner r) r)
        _ (when live (room/world installation live))
        now (if live (clock/current-time (:context (:world r))) (System/currentTimeMillis))]
    (when-not (= record (store/room-record (:control installation) (:room/id record))) (v/fail! :room-offline {}))
    {:reconciled
     (into [] (comp (filter #(and (= (:room/id record) (get-in % [:ask/intent :room/id]))
                                  (or (nil? (:ask/terminal %))
                                      (and live (contains? @(get-in live [:owner :asks]) (:ask/id %))))))
                    (keep (fn [ask]
                            (let [intent (:ask/intent ask)
                                  candidate (or (:ask/terminal ask)
                                                (when (<= (:deadline intent) now) {:status :timeout})
                                                (when (store/session-ended? (:control installation) (:session intent))
                                                  {:status :leave :reason :participant-left})
                                                (when (not= (:process installation) (:process intent))
                                                  {:status :leave :reason :process-lost}))]
                              (when candidate
                                (if live (finish! installation live (:ask/id ask) candidate)
                                    (store/terminal! (:control installation) (:ask/id ask) candidate))))))
                    (take 256)) (store/asks (:control installation)))}))
