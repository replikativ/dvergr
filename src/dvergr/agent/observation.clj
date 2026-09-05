(ns dvergr.agent.observation
  "Bounded, capability-scoped projections over one agent execution tree.

   This is an observer, not a lifecycle owner or a second event log. Durable
   Runs and Room messages remain authoritative; this namespace joins them into
   a compact value suitable for the host REPL, SCI programs, UIs, and eval
   traces. A non-nil scope Run can see only itself and structural descendants."
  (:require [dvergr.activity :as activity]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as discourse]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]))

(def ^:private default-run-limit 200)
(def ^:private default-message-limit 200)
(def ^:private default-content-limit 240)
(def ^:private default-content-budget 12000)
(def ^:private default-detail-limit 100)
(def ^:private maximum-run-limit 500)
(def ^:private maximum-message-limit 500)
(def ^:private maximum-content-limit 1000)
(def ^:private maximum-content-budget 64000)
(def ^:private maximum-detail-limit 500)
(def ^:private maximum-message-activities 20)
(def ^:private maximum-message-tool-uses 20)
(def ^:private maximum-resource-runs 32)
(def ^:private active-statuses #{:running :cancelling})
(def ^:private option-keys
  #{:run-limit :message-limit :content-limit :content-budget :detail-limit})
(def ^:private missing-balance ::missing-balance)
(def ^:private maximum-live-receipts 4096)
(defonce ^:private issued-receipts (atom {}))

(defn issue-receipt!
  "Issue a process-authenticated receipt for one scoped SCI inspection.

   The durable activity is the audit projection. This bounded live registry is
   separate verifier authority: writable Room data alone cannot forge proof
   that the trusted SCI closure actually executed."
  [run-id]
  (let [receipt-id (random-uuid)
        key [run-id receipt-id]
        issued-at (System/nanoTime)]
    (swap! issued-receipts
           (fn [receipts]
             (let [receipts (assoc receipts key issued-at)]
               (if (<= (count receipts) maximum-live-receipts)
                 receipts
                 (into {} (take-last maximum-live-receipts
                                     (sort-by val receipts)))))))
    receipt-id))

(defn revoke-receipt!
  "Remove an issued receipt when its durable activity could not be written."
  [run-id receipt-id]
  (swap! issued-receipts dissoc [run-id receipt-id])
  nil)

(defn consume-receipt!
  "Verify and consume a live receipt exactly once. Intended for trusted evals."
  [run-id receipt-id]
  (let [key [run-id receipt-id]
        [before _] (swap-vals! issued-receipts dissoc key)]
    (contains? before key)))

(defn- validate-options [opts]
  (when-not (map? opts)
    (throw (ex-info "Observation options must be a map"
                    {:type ::invalid-options :value opts})))
  (when-let [unknown (seq (remove option-keys (keys opts)))]
    (throw (ex-info "Unknown observation options"
                    {:type ::unknown-options
                     :unknown (set unknown)
                     :allowed option-keys})))
  opts)

(defn- bounded-positive [opts key default maximum]
  (let [value (get opts key default)]
    (when-not (and (integer? value) (pos? value) (<= value maximum))
      (throw (ex-info (str key " must be a positive integer no greater than " maximum)
                      {:type ::invalid-limit
                       :key key
                       :value value
                       :maximum maximum})))
    value))

(defn- content-preview [content limit]
  (when (and (some? content) (pos? limit))
    (let [value (str content)]
      (if (<= (count value) limit)
        value
        (str (subs value 0 (dec limit)) "…")))))

(defn- compact-value [value]
  (when (some? value)
    (content-preview value 80)))

(defn- portable-time [value]
  (if (instance? java.util.Date value)
    (.getTime ^java.util.Date value)
    value))

(defn- run-summary [candidate cause-limit]
  (let [all-causes (vec (:run/caused-by candidate))
        causes (take cause-limit all-causes)]
    (cond->
     (-> (select-keys candidate
                      [:run/id :run/kind :run/room :run/actor :run/trigger
                       :run/parent :run/status :run/started-at
                       :run/ended-at :run/world-id :run/settlement-status
                       :run/settlement-reason])
         (update :run/started-at portable-time)
         (update :run/ended-at portable-time))
      (seq causes)
      (assoc :run/caused-by (set causes)
             :run/caused-by-count (count all-causes)
             :run/caused-by-truncated? (> (count all-causes) cause-limit))
      (:run/reason candidate)
      (assoc :run/reason (compact-value (:run/reason candidate)))
      (:run/error candidate)
      (assoc :run/error (compact-value (:run/error candidate))))))

(defn- run-summaries [runs detail-limit]
  (reduce (fn [{:keys [remaining] :as state} candidate]
            (let [cause-count (count (:run/caused-by candidate))
                  allowed (min remaining cause-count)]
              (-> state
                  (update :summaries conj (run-summary candidate allowed))
                  (assoc :remaining (- remaining allowed)))))
          {:remaining detail-limit :summaries []}
          runs))

(defn- structural-depth [runs-by-id candidate]
  (loop [current candidate depth 0 seen #{}]
    (let [parent-id (:run/parent current)]
      (if (and parent-id (not (contains? seen parent-id)))
        (if-let [parent (get runs-by-id parent-id)]
          (recur parent (inc depth) (conj seen parent-id))
          depth)
        depth))))

(defn- run-order [runs-by-id candidate]
  [(structural-depth runs-by-id candidate)
   (some-> ^java.util.Date (:run/started-at candidate) .getTime)
   (str (:run/id candidate))])

(defn- activity-summary [fact]
  (cond->
   (update (select-keys fact
                        [:activity/id :activity/kind :activity/verb :activity/at
                         :activity/run-id :activity/tool-use-id :activity/status
                         :activity/critical?])
           :activity/at portable-time)
    (:activity/tool-name fact)
    (assoc :activity/tool-name (compact-value (:activity/tool-name fact)))
    (:activity/outcome fact)
    (assoc :activity/outcome (compact-value (:activity/outcome fact)))))

(defn- message-summary [message preview-limit activity-limit tool-use-limit]
  (let [all-facts (activity/message-activities message)
        all-tool-uses (or (:tool-uses message)
                          (get-in message [:metadata :tool-uses]))
        facts (take activity-limit all-facts)
        tool-uses (take tool-use-limit all-tool-uses)
        content (some-> (:content message) str)]
    (cond-> {:message/id (:id message)
             :message/from (:from message)
             :message/to (:to message)
             :message/at (portable-time (:ts message))
             :message/in-reply-to (:in-reply-to message)
             :message/thread-root-id (discourse/thread-root-id message)
             :message/run-id (activity/message-run-id message)}
      content
      (assoc :message/content-truncated? (> (count content) preview-limit))

      (and content (pos? preview-limit))
      (assoc :message/content-preview (content-preview content preview-limit))

      (seq facts)
      (assoc :message/activities (mapv activity-summary facts)
             :message/activity-count (count all-facts)
             :message/activities-truncated?
             (> (count all-facts) activity-limit))

      (seq tool-uses)
      (assoc :message/tool-uses
             (mapv (fn [tool-use]
                     {:tool-use/id (compact-value
                                    (or (:tool-use/id tool-use)
                                        (:id tool-use)))
                      :tool-use/name (compact-value
                                      (or (:tool-use/name tool-use)
                                          (:name tool-use)))})
                   tool-uses)
             :message/tool-use-count (count all-tool-uses)
             :message/tool-uses-truncated?
             (> (count all-tool-uses) tool-use-limit)))))

(defn- message-summaries [messages content-limit content-budget detail-limit]
  (reduce (fn [{:keys [content-remaining detail-remaining] :as state} message]
            (let [content-size (count (str (or (:content message) "")))
                  preview-limit (min content-limit content-remaining content-size)
                  activity-count (count (activity/message-activities message))
                  activity-limit (min maximum-message-activities
                                      detail-remaining activity-count)
                  after-activities (- detail-remaining activity-limit)
                  tool-use-count
                  (count (or (:tool-uses message)
                             (get-in message [:metadata :tool-uses])))
                  tool-use-limit (min maximum-message-tool-uses
                                      after-activities tool-use-count)]
              (-> state
                  (update :summaries conj
                          (message-summary message preview-limit
                                           activity-limit tool-use-limit))
                  (assoc :content-remaining (- content-remaining preview-limit)
                         :detail-remaining (- after-activities tool-use-limit)))))
          {:content-remaining content-budget
           :detail-remaining detail-limit
           :summaries []}
          messages))

(defn- balance-if-present [read-balance]
  (try
    (read-balance)
    (catch clojure.lang.ExceptionInfo error
      (if (= :kontor.resource/account-not-found (:type (ex-data error)))
        missing-balance
        (throw error)))))

(defn- resource-view [room scope-run-id frontier-runs]
  (when (satisfies? store/PResourceStore (:store room))
    (let [scope-balance
          (balance-if-present
           #(if scope-run-id
              (resource/run-balance room scope-run-id)
              (resource/balance room)))]
      (when-not (= missing-balance scope-balance)
        (let [bounded-runs (take maximum-resource-runs frontier-runs)]
          {:scope scope-balance
           :runs
           (into (sorted-map-by #(compare (str %1) (str %2)))
                 (keep (fn [candidate]
                         (let [balance
                               (balance-if-present
                                #(resource/run-balance room (:run/id candidate)))]
                           (when-not (= missing-balance balance)
                             [(:run/id candidate) balance]))))
                 bounded-runs)
           :possibly-truncated?
           (> (count frontier-runs) maximum-resource-runs)})))))

(defn snapshot
  "Return a compact observation of a Room's execution tree.

   `scope-run-id` is a capability boundary: when present, only that structural
   Run subtree and messages correlated with it are visible. Nil is the trusted
   Room-operator view. Trigger messages are included even though they precede
   and therefore are not produced by the Run.

   Options are deliberately bounded because the returned value may enter an
   LLM context: :run-limit (default 200, max 500), :message-limit (default 200,
   max 500), :content-limit per message (default 240, max 1000),
   :content-budget across the snapshot (default 12000, max 64000), and
   :detail-limit across causes, activities, and tool uses (default 100, max
   500)."
  ([room scope-run-id] (snapshot room scope-run-id {}))
  ([room scope-run-id opts]
   (validate-options opts)
   (when (and scope-run-id (not (uuid? scope-run-id)))
     (throw (ex-info "Observation scope must be a Run UUID"
                     {:type ::invalid-scope :scope-run-id scope-run-id})))
   (let [run-limit (bounded-positive opts :run-limit default-run-limit
                                     maximum-run-limit)
         message-limit (bounded-positive opts :message-limit
                                         default-message-limit
                                         maximum-message-limit)
         content-limit (bounded-positive opts :content-limit
                                         default-content-limit
                                         maximum-content-limit)
         content-budget (bounded-positive opts :content-budget
                                          default-content-budget
                                          maximum-content-budget)
         detail-limit (bounded-positive opts :detail-limit default-detail-limit
                                        maximum-detail-limit)
         runs (run/runs room (cond-> {:limit run-limit}
                               scope-run-id (assoc :root-run-id scope-run-id)))]
     (when (and scope-run-id
                (not-any? #(= scope-run-id (:run/id %)) runs))
       (throw (ex-info "Observation scope is not a Run in this Room"
                       {:type ::unknown-scope
                        :room-id (:id room)
                        :scope-run-id scope-run-id})))
     (let [runs-by-id (into {} (map (juxt :run/id identity)) runs)
           ordered-runs (sort-by #(run-order runs-by-id %) runs)
           run-projection (run-summaries ordered-runs detail-limit)
           visible-runs (:summaries run-projection)
           visible-ids (into #{} (map :run/id) visible-runs)
           trigger-ids (into #{} (keep :run/trigger) visible-runs)
           raw-messages (discourse/messages
                         room {:limit message-limit
                               :run-ids visible-ids
                               :message-ids trigger-ids})
           message-projection
           (message-summaries raw-messages content-limit content-budget
                              (:remaining run-projection))
           visible-messages (:summaries message-projection)
           activities (into []
                            (mapcat #(or (:message/activities %) []))
                            visible-messages)
           frontier-runs (filterv #(contains? active-statuses (:run/status %))
                                  visible-runs)
           frontier (mapv :run/id frontier-runs)
           failures (->> visible-runs
                         (filter #(or (= :failed (:run/status %))
                                      (:run/error %)
                                      (= :settlement-failed
                                         (:run/settlement-reason %))))
                         (mapv #(select-keys % [:run/id :run/actor :run/status
                                                :run/reason :run/error
                                                :run/settlement-status
                                                :run/settlement-reason])))
           resources (resource-view room scope-run-id frontier-runs)
           preview-size (reduce + 0
                                (map #(count (or (:message/content-preview %) ""))
                                     visible-messages))]
       (cond->
        {:observation/room-id (:id room)
         :observation/scope-run-id scope-run-id
         :observation/runs visible-runs
         :observation/frontier frontier
         :observation/messages visible-messages
         :observation/activities activities
         :observation/failures failures
         :observation/summary
         {:runs (count visible-runs)
          :active (count frontier)
          :messages (count visible-messages)
          :activities (count activities)
          :failures (count failures)
          :possibly-truncated?
          (or (= run-limit (count runs))
              (= message-limit (count raw-messages))
              (>= preview-size content-budget)
              (zero? (:detail-remaining message-projection)))}}
         resources (assoc :observation/resources resources))))))
