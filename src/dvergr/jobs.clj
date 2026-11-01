(ns dvergr.jobs
  "Long operations as durable Runs: started by one call, then polled, waited
   on or cancelled by later calls, from any client.

   A client's single call cannot outlast its timeout (Codex allows 60 s by
   default, Claude Code backgrounds a call after 2 minutes), while a set of
   agent attempts takes minutes. So such an op starts a job and returns its
   id; `status` long-polls it.

   A job IS a Run of its room (`:run/kind` in `kinds`), stored before its work
   starts, and the Runs its work starts are its structural children
   (`:run/parent`): an evaluation takes the job's id as `:parent-run`. So a
   job, its attempts and their Attempts are one tree in the room's store,
   visible to progress, the Run tree and simmis alike, and a job outlives the
   daemon: after a restart its Run reads `:failed` (orphaned) or whatever it
   finished as. Only the handles to wait on and cancel the work live in this
   process."
  (:require [dvergr.runtime.threads :as threads]
            [dvergr.agent.run :as run]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.spin.core :as spin-core]
            [taoensso.telemere :as tel]))

(def kinds
  "The Run kinds a job has."
  store/job-run-kinds)

(def max-wait-ms
  "Longest a status call blocks: below the shortest common client timeout."
  25000)

;; The existing process-local Job owner retains cleanup stages/callbacks and
;; waiters until native acknowledgement. Execution status remains the Run's.
(defonce ^:private live (atom {}))

(defn job-room
  "The room job `id` (its Run's uuid) is stored in, or nil."
  [id]
  (when (uuid? id)
    (or (get-in @live [id :room])
        (some (fn [room]
                (when (and (:store room)
                           (binding [ec/*execution-context* (:ctx room)] (run/run room id)))
                  room))
              (rreg/list-rooms :where #(some? (:store %)))))))

(defn owner
  "Who started job `id`: `{:room <Room> :owner <actor>}` (its Run's actor,
   `:dvergr` unless `start!` was given one), or nil for an unknown job."
  [id]
  (when-let [room (job-room id)]
    {:room room
     :owner (:run/actor (binding [ec/*execution-context* (:ctx room)] (run/run room id)))}))

(defn- job [room r]
  (when r
    {:id (:run/id r)
     :kind (:run/kind r)
     :room (:id room)
     :owner (:run/actor r)
     :status (:run/status r)
     :started-at (:run/started-at r)
     :finished-at (:run/ended-at r)
     :error (:run/error r)
     :reason (:run/reason r)}))

(defn- error-message [error]
  (try
    (let [message (or (ex-message error) (.getName (class error)))]
      (subs message 0 (min 4096 (count message))))
    (catch Throwable _ "Job failed")))

(def ^:private next-stage
  {:before-finish :core-ack :core-ack :persist :persist :settled
   :settled :adapter-ack :adapter-ack :release :release :done})

(defn- retained-run! [{:keys [id room state]}]
  (let [{:keys [outcome]} @state
        {:keys [status opts]} outcome
        active (some #(when (= id (:run/id %)) %) (run/active-runs (:id room)))]
    (when-not (and active (= status (:run/status active))
                   (= (:now opts) (:run/ended-at active))
                   (= (:reason opts) (:run/reason active))
                   (= (some-> (:error opts) ex-message) (:run/error active)))
      (throw (ex-info "Job terminal persistence is not confirmed"
                      {:type ::terminal-unconfirmed :job id})))
    active))

(defn- perform-stage! [{:keys [id room before-finish settled state] :as owner} stage]
  (let [{:keys [status value error opts]} (:outcome @state)]
    ;; The execution context may be destroyed by adapter retirement. All
    ;; lifecycle/cleanup callbacks use the surviving control Room context.
    (binding [ec/*execution-context* (:ctx room)]
      (case stage
        :before-finish (do (run/await-family! room id)
                           (when before-finish (before-finish id status (or error value))))
        :core-ack (run/acknowledge-job-cleanup! room id :core)
        :adapter-ack (run/acknowledge-job-cleanup! room id :adapter)
        :persist (do (run/retain-finished! id status opts) (retained-run! owner))
        :settled (when settled (settled status (or error value)))
        :release (when (some #(= id (:run/id %)) (run/active-runs (:id room)))
                   (retained-run! owner)
                   (run/release-finished! id))))))

(defn- drive-finish! [{:keys [id done state] :as owner}]
  (loop []
    (let [stage (:stage @state)]
      (if (= :done stage)
        (do
          ;; No Room/context access follows release of the active Run lease.
          (reset! state {:phase :done})
          (swap! live dissoc id)
          (deliver done true)
          {:id id :settled? true})
        (let [failure (try (perform-stage! owner stage) nil (catch Throwable error error))]
          (if failure
            (do
              ;; Keep the original callbacks/outcome private in this existing
              ;; Job owner. Every failed attempt gets a distinct recovery token.
              (swap! state assoc :phase :held :error failure :token (random-uuid))
              (tel/log! {:level :warn :id ::cleanup-held
                         :data {:job id :stage stage :error (error-message failure)}}
                        "Job cleanup requires explicit HOST recovery")
              {:id id :settled? false :cleanup-required? true :cleanup-stage stage})
            (do (swap! state assoc :stage (get next-stage stage)) (recur))))))))

(defn- finish-owned! [{:keys [state] :as owner} status value error reason]
  (let [opts (cond-> {:now (java.util.Date.)}
               error (assoc :error (ex-info (error-message error) {}))
               reason (assoc :reason reason))]
    (reset! state {:phase :finishing :stage :before-finish
                   :outcome {:status status :value value :error error :opts opts}})
    (drive-finish! owner)))

(defn recovery-state
  "HOST-only exact held-stage locator. Not a tool or SCI capability. Callers
   reconcile the original native operation before retrying or acknowledging it;
   tokens are coordination data, not authorization or proof of cleanup."
  [id]
  (when-let [state (:state (get @live id))]
    (let [current @state]
      (when (= :held (:phase current)) (select-keys current [:stage :token])))))

(defn recover!
  "HOST-only recovery of this process's original Job owner. EXPECTED is the
   exact recovery-state read after native reconciliation. ACTION is :retry
   (only if safe to repeat that failed callback) or :acknowledge (HOST attests
   that stage's physical outcome, without calling it again). Successful stages
   never repeat. No timers, new execution or recovery from replacement Rooms."
  [id expected action]
  (when (simple/on-drain-thread?)
    (throw (ex-info "Job recovery requires a HOST worker thread" {:type ::engine-thread})))
  (when-not (#{:retry :acknowledge} action)
    (throw (ex-info "Invalid Job recovery action" {:type ::invalid-recovery})))
  (let [{:keys [state] :as owner} (get @live id)
        current (when state @state)]
    (when-not (and state (= :held (:phase current))
                   (= expected (select-keys current [:stage :token]))
                   (compare-and-set! state current (-> current (assoc :phase :finishing) (dissoc :token :error))))
      (throw (ex-info "Job cleanup recovery does not own this held stage"
                      {:type ::stale-recovery :job id})))
    (try
      (when (= :acknowledge action)
        ;; A persistence acknowledgement cannot invent a missing durable ACK.
        (when-let [ack-stage ({:core-ack :core :adapter-ack :adapter} (:stage current))]
          (let [room (:room owner)
                durable (binding [ec/*execution-context* (:ctx room)] (run/run room id))]
            (when-not (and (store/job-cleanup-acknowledged? durable ack-stage)
                           (store/family-return-acknowledged? durable))
              (throw (ex-info "Job cleanup acknowledgement is not durable"
                              {:type ::cleanup-ack-unconfirmed :job id :stage (:stage current)})))
            ;; Lost ACK may have left the live Run cache stale. Confirm and
            ;; synchronize it through the same original-owner writer.
            (run/acknowledge-job-cleanup! room id ack-stage)))
        ;; A persistence acknowledgement cannot invent the retained Run lease.
        (when (= :persist (:stage current)) (retained-run! owner))
        (when (and (= :release (:stage current))
                   (some #(= id (:run/id %)) (run/active-runs (:id (:room owner)))))
          (throw (ex-info "Job execution lease release is not confirmed"
                          {:type ::release-unconfirmed :job id})))
        (swap! state assoc :stage (get next-stage (:stage current))))
      (drive-finish! owner)
      (catch Throwable error
        (swap! state assoc :phase :held :error error :token (random-uuid))
        (throw error)))))

(defn- start-unowned!
  "Start `(work job-id)`, a Spin, as one durable Job Run. Work construction is
   synchronous, then its monitor waits off the engine drain. `:before-finish`
   joins physical work/returns resources before terminal persistence. `:settled`
   retires adapters after terminal persistence while the Run's execution lease
   is still held. Callbacks run under the control Room context, even when :ctx
   names a separate work context. Cleanup failure retains this Job owner and
   its active lease until explicit HOST recovery; :settled? remains false.
   :parent names a parent Run and :actor the owner (default :dvergr). `finish`
   observes the work value; `reason-of` supplies an optional reason keyword."
  [room {:keys [id kind parent ctx actor]} work & {:keys [finish settled reason-of before-finish]}]
  (when-not (satisfies? store/PRoomStore (:store room))
    (throw (ex-info "Jobs require a durable Room store" {:type ::durable-store-required})))
  (when-not (contains? kinds kind)
    (throw (ex-info "Unknown job kind" {:type ::unknown-kind :kind kind :kinds kinds})))
  (let [ctx (or ctx (:ctx room))
        r (binding [ec/*execution-context* (:ctx room)]
            (run/start! room (or actor :dvergr) (random-uuid) nil
                        (cond-> {:id id :kind kind} parent (assoc :parent parent))))
        id (:run/id r)
        owner {:id id :room room :done (promise) :state (atom {:phase :working})
               :before-finish before-finish :settled settled}]
    (swap! live assoc id owner)
    (run/register-cancel-hook! id ::family #(run/cancel-family! room id))
    (try
      (when (run/cancel-requested? id)
        (throw (java.util.concurrent.CancellationException. "Job admission cancelled")))
      (let [spin (binding [ec/*execution-context* ctx] (work id))]
        (run/register-cancel-hook! id ::spin
                                   #(binding [ec/*execution-context* ctx]
                                      (spin-core/cancel-spin! spin)))
        (threads/native-future
         (binding [simple/*in-drain?* false ec/*spin-id* nil]
           (let [[status value error reason]
                 (try
                   (let [value (binding [ec/*execution-context* ctx] @spin)]
                     (when finish (finish value))
                     [:completed value nil (when reason-of (reason-of value))])
                   (catch Throwable error
                     (if (run/cancel-requested? id)
                       [:cancelled nil nil nil]
                       [:failed nil error nil])))]
             (finish-owned! owner status value error reason)))))
      (catch Throwable error
        ;; No monitor owns synchronous construction failure. The SAME staged
        ;; owner joins cleanup and adapter retirement exactly once here.
        (finish-owned! owner (if (run/cancel-requested? id) :cancelled :failed) nil error nil)
        (throw error)))
    (job room r)))

(defn start!
  "Start a durable Job under its original parent's admission lease. The lease
   precedes work construction and survives exceptions after Run admission."
  [room opts work & options]
  (let [id (or (:id opts) (random-uuid))]
    (run/call-with-child-admission!
     room (:parent opts) id
     (fn [_] (apply start-unowned! room (assoc opts :id id) work options)))))

(defn- settlement-observation [room r]
  (let [id (:run/id r)]
    (when (and r (kinds (:run/kind r)))
      (let [family? (store/family-return-acknowledged? r)
            core? (store/job-cleanup-acknowledged? r :core)
            adapter? (store/job-cleanup-acknowledged? r :adapter)
            owner (get @live id)
            lease? (some #(= id (:run/id %)) (run/active-runs (:id room)))
            terminal? (contains? store/terminal-run-statuses (:run/status r))]
        {:id id :outcome (:run/status r)
         :family-return (cond-> {:state (if family? :acknowledged (if owner :pending :unknown))}
                          family? (assoc :ack (:run/family-return-ack r)))
         :core-cleanup (cond-> {:state (if core? :acknowledged (if owner :pending :unknown))}
                         core? (assoc :ack (:run/job-core-cleanup-ack r)))
         :adapter-cleanup (cond-> {:state (if adapter? :acknowledged (if owner :pending :unknown))}
                            adapter? (assoc :ack (:run/job-adapter-cleanup-ack r)))
         :owner-state (if owner :live :missing)
         :execution-lease (if lease? :held :absent)
         :settled? (boolean (and terminal? family? core? adapter? (not owner) (not lease?)))}))))

(defn observe-settlement
  "HOST facts for a Job in an explicit Room, including after restart. Native
   witnesses certify configured hooks, not arbitrary effects or acceptance.
   Missing owner without witnesses is unknown, never settled."
  [room id]
  (settlement-observation room (binding [ec/*execution-context* (:ctx room)] (run/run room id))))

(defn status
  "The job `id` (its Run's id), waiting up to `wait-ms` (capped at
   `max-wait-ms`) for it to finish; nil for an unknown id. Also returns the
   room it is stored in (`:room-value`) for callers that read its results.
   Held native cleanup reports :cleanup-required? and a stage keyword; private
   errors, callbacks, outcome handles and recovery tokens are not projected."
  ([id] (status id 0))
  ([id wait-ms]
   (when-let [room (job-room id)]
     (when (pos? (or wait-ms 0))
       (when-let [done (get-in @live [id :done])]
         (deref done (min max-wait-ms (long wait-ms)) nil)))
     (let [r (binding [ec/*execution-context* (:ctx room)] (run/run room id))]
       ;; Terminal persistence alone is insufficient: the retained lease and
       ;; this owner remain until every native cleanup stage acknowledges.
       (let [current (some-> (get @live id) :state deref)]
         (some-> (job room r)
                 (assoc :room-value room :settled? (true? (:settled? (settlement-observation room r))))
                 (cond-> (= :held (:phase current))
                   (assoc :cleanup-required? true :cleanup-stage (:stage current)))))))))

(defn cancel!
  "Stop the job `id`: request cancellation of its Run, which stops its work
   and settles it `:cancelled`. Returns the job, or nil for an unknown id. A
   finished job is returned unchanged."
  [id]
  (when (job-room id)
    (when (run/cancel-run! id)
      (when-let [done (get-in @live [id :done])]
        (deref done max-wait-ms nil)))
    (status id)))

(defn jobs
  "Jobs, newest first: the job Runs of `room`, or of every durable room."
  ([] (jobs nil))
  ([room]
   (->> (if room [room] (rreg/list-rooms :where #(some? (:store %))))
        (mapcat (fn [r]
                  (binding [ec/*execution-context* (:ctx r)]
                    (->> (run/runs r {:limit 1000})
                         (filter #(contains? kinds (:run/kind %)))
                         (map #(job r %))))))
        (sort-by #(some-> ^java.util.Date (:started-at %) .getTime) #(compare %2 %1))
        vec)))
