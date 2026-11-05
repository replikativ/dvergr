(ns dvergr.agent.subexperiment
  "An SCI child experiment uses ordinary Jobs, evaluation and conserved wallets.
   This adapter owns no candidate state and introduces no new execution engine."
  (:require [dvergr.runtime.threads :as threads]
            [dvergr.agent.experiment-admission :as admission]
            [dvergr.agent.authority :as authority]
            [dvergr.agent.invocations :as invocations]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.jobs :as jobs]
            [dvergr.resource :as resource]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- quiesce! [room group]
  ;; A timeout is not permission to refund a live child. Failed tasks have
  ;; quiesced but still make the job fail; retain that evidence across joins.
  (loop [failure nil]
    (let [error (try (evaluation/await-cleanups-for! room group) nil (catch Throwable t t))]
      (if error
        (do (Thread/sleep 25)
            (recur (if (some :error (:failures (ex-data error))) (or failure error) failure)))
        failure))))

(defn start!
  "Start a preflighted child Job and return a Spin of its certified result.
   Parent ownership is reserved BEFORE Job admission, even if the returned
   Spin is ignored. The Job stays live until physical cleanup and wallet return.
   HOST-only :candidate-options may replace the child delegation scope with
   both explicit :experiment-capabilities and :delegation-policy. It does not
   change the original admission, ownership or funding authority.
   HOST :observation-ref binds a receipt to the exact offered invocation;
   :return-receipt? returns that receipt instead of a passive result Spin."
  ([control-room work-room team definition opts authority]
   (start! control-room work-room team definition opts authority {}))
  ([control-room work-room team definition opts authority host-options]
   (when-not (and (map? host-options) (every? #{:world-parent :admit! :settled! :candidate-options :observation-ref :return-receipt?} (keys host-options))
                  (or (not (contains? host-options :observation-ref))
                      (invocations/valid-ref? (:observation-ref host-options)))
                  (or (not (contains? host-options :return-receipt?))
                      (and (boolean? (:return-receipt? host-options))
                           (:observation-ref host-options) (uuid? (:parent-run authority))))
                  (or (not (contains? host-options :world-parent))
                      (some? (:world-parent host-options)))
                  (every? #(or (not (contains? host-options %)) (fn? (get host-options %))) [:admit! :settled!]))
     (throw (ex-info "Invalid HOST child experiment options" {:type ::invalid-host-options})))
   (let [candidate-options
         (if (contains? host-options :candidate-options)
           (let [options (:candidate-options host-options)]
             (when-not (and (map? options)
                            (= (set authority/host-option-keys) (set (keys options)))
                            (map? (:experiment-capabilities options)))
               (throw (ex-info "Candidate options require an explicit scope and delegation policy"
                               {:type ::invalid-host-options})))
             (authority/validate-policy! (:delegation-policy options))
             (invocations/refs (:experiment-capabilities options))
             options)
           (select-keys authority authority/host-option-keys))
         {:keys [resources parallelism capabilities]} (admission/plan team definition opts authority)
         parent (:parent-run authority)
         ctx (:ctx work-room)
         world-parent (get host-options :world-parent work-room)
         execution-ctx (:ctx world-parent)
         done (binding [ec/*execution-context* ctx] (sp/deferred))
         settled (promise)
         group (evaluation/cleanup-group)
         allocation (atom (if (seq resources) :not-started :not-requested))
         admit!
         (fn []
           (let [job
                 (jobs/start!
                  control-room (cond-> {:kind :experiment :ctx execution-ctx}
                                 parent (assoc :parent parent)
                                 (:observation-ref host-options)
                                 (assoc :observation {:offer (invocations/content-id (:observation-ref host-options))
                                                      :experiment (:experiment/content-id definition)}))
                  (fn [id]
                    (when-let [admit! (:admit! host-options)] (admit! id))
                    (when (seq resources)
                      (reset! allocation :attempting)
                      (resource/allocate-run! control-room id parent resources)
                      (reset! allocation :allocated))
                    (experiment/run
                     control-room team definition (:evaluators capabilities)
                     (merge candidate-options
                            {:world-setups (:world-setups capabilities)
                             :parallelism parallelism :parent-run id :world-parent world-parent
                             :cleanup-group group :complete-only? true})))
                  :before-finish
                  (fn [id _ _]
                    (let [failure (quiesce! control-room group)]
                      (resource/reconcile-return! control-room id parent allocation)
                      (when failure (throw failure))))
                  :settled
                  (fn [status value]
                    (when-let [settled! (:settled! host-options)]
                       ;; A failed physical release leaves the Job AND parent
                       ;; acknowledgement held. Never publish before this hook.
                      (settled! status value))
                    (try
                      (binding [ec/*execution-context* ctx]
                        (sp/deliver! done (cond-> {:status status}
                                            (= :completed status)
                                            (merge (select-keys value [:scorecard :incomplete :refused :stale-cells]))
                                            (instance? Throwable value) (assoc :error (ex-message value)))))
                      (finally (deliver settled true)))))]
             {:run/id (:id job)
              ::program/on-settled! (fn [acknowledge!] (threads/native-future @settled (acknowledge!)))
              ::program/cancel! #(run/cancel-room-run! (:id control-room) (:id job))}))
         handle (if-let [own! (:own-child! authority)] (own! admit!) (admit!))
         id (:run/id handle)]
    ;; Trusted legacy callers can supply a structural parent without a paid
    ;; Run supervisor. Keep their existing cancellation behavior as well.
     (when (and parent (nil? (:own-child! authority)))
       (run/register-cancel-hook! parent [::child id]
                                  #(run/cancel-room-run! (:id control-room) id)))
     (if (:return-receipt? host-options)
       {:job id :owner-run parent :invocation (:observation-ref host-options)}
       (binding [ec/*execution-context* ctx]
         (sp/spin (assoc (sp/await done) :job id)))))))
