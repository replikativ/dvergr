(ns dvergr.agent.experiment-observation
  "Scoped observation of an already admitted child Job. No admission, wallet,
   execution, cleanup, or result-selection authority is introduced here."
  (:require [dvergr.agent.experiment :as experiment]
            [dvergr.agent.invocations :as invocations]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.public-feedback :as feedback]
            [dvergr.agent.run :as run]
            [dvergr.jobs :as jobs]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- refuse! [reason]
  (throw (ex-info "Child experiment observation refused" {:type ::refused :reason reason})))

(defn- scope! [{:keys [control-room run-id agent-program-ceiling deadline-nanos]}]
  (when-not (and control-room (uuid? run-id) (integer? deadline-nanos)
                 (= run-id (:parent-run agent-program-ceiling))
                 (identical? control-room (:control-room agent-program-ceiling)))
    (refuse! :missing-native-scope))
  (run/assert-unlocked-host-thread! control-room ::worker-required)
  (when (or ec/*spin-id* (.isInterrupted (Thread/currentThread)))
    (throw (InterruptedException. "Child observation cannot execute on this thread")))
  (when (or (run/cancel-requested? run-id) (>= (System/nanoTime) deadline-nanos))
    (refuse! :owner-expired))
  (let [parent (binding [ec/*execution-context* (:ctx control-room)] (run/run control-room run-id))]
    (when-not (= :running (:run/status parent)) (refuse! :owner-not-running)))
  (into {} (map (juxt invocations/content-id identity))
        (invocations/refs (:experiment-capabilities agent-program-ceiling))))

(defn- admitted? [parent offers child]
  (and (= :experiment (:run/kind child)) (= parent (:run/parent child))
       (contains? offers (:run/job-offer child)) (uuid? (:run/job-experiment child))))

(defn- bounded-page [result]
  ;; Keep a continuation when public feedback makes a page larger. Unicode
  ;; names count by encoded size, not just by the number of characters.
  (loop [page result]
    (if (<= (alength (.getBytes (pr-str page) java.nio.charset.StandardCharsets/UTF_8)) 24000)
      page
      (if (> (count (:entries page)) 1)
        (let [entries (pop (:entries page))]
          (recur (assoc page :entries entries :next-offset (+ (:offset page) (count entries)))))
        (assoc (select-keys page [:job :status :settled? :result-state :scorecard-id :entry-count])
               :projection-omitted :size-limit)))))

(defn children
  "Recover receipts after a lost start response. Bounded, direct children only;
   never retry admission. Offset continues the bounded direct-child listing."
  [{:keys [control-room run-id] :as ctx} offset]
  (when-not (and (integer? offset) (<= 0 offset 1000000)) (refuse! :invalid-input))
  (let [offers (scope! ctx)
        rows (binding [ec/*execution-context* (:ctx control-room)]
               (run/runs control-room {:parent-run-id run-id :limit 129 :offset offset}))
        _ (scope! ctx)]
    (cond-> {:children (->> (take 128 rows) (filter #(admitted? run-id offers %))
                            (mapv #(hash-map :job (:run/id %) :status (:run/status %))))
             :offset offset}
      (> (count rows) 128) (assoc :next-offset (+ offset 128)))))

(defn await!
  "Wait once in the supervised HOST worker, then read the exact terminal
   receipt. Repeating this observation never starts or scores anything.
   OFFSET pages a fixed projection, never hidden task/checker/evidence data."
  [{:keys [control-room run-id deadline-nanos] :as ctx} id offset]
  (when-not (and (uuid? id) (integer? offset) (<= 0 offset 4096)) (refuse! :invalid-input))
  (let [offers (scope! ctx)
        child (binding [ec/*execution-context* (:ctx control-room)] (run/run control-room id))]
    (when-not (admitted? run-id offers child) (refuse! :not-admitted-child))
    (let [disclosure (get-in offers [(:run/job-offer child) :invocation/public-feedback])
          page-size (if disclosure feedback/page-size 32)
          status (jobs/await-in! control-room id deadline-nanos)
          child (binding [ec/*execution-context* (:ctx control-room)] (run/run control-room id))
          base {:job id :status (:status status) :settled? true :result-state (:run/job-result-state child)}
          result
          (if (= :available (:run/job-result-state child))
            (let [card (experiment/scorecard-bounded control-room (:run/job-scorecard child))
                  entries (:scorecard/entries card)]
              (when-not (and card (= (:run/job-scorecard child) (:scorecard/content-id card))
                             (= (:run/job-experiment child)
                                (get-in card [:scorecard/experiment :experiment/content-id])))
                (refuse! :result-binding-mismatch))
              ;; Certified Attempt ids are their Run ids. A same-Experiment
              ;; scorecard from an earlier invocation is still the wrong result.
              (doseq [entry entries]
                (let [attempt-run (binding [ec/*execution-context* (:ctx control-room)]
                                    (run/run control-room (:attempt/id entry)))]
                  (when-not (= id (:run/parent attempt-run)) (refuse! :foreign-result-attempt))))
              (cond-> (assoc base :scorecard-id (:scorecard/content-id card)
                             :entry-count (count entries) :offset offset
                             :entries
                             (mapv (fn [entry]
                                     (let [row (binding [ec/*execution-context* (:ctx control-room)]
                                                 (run/run control-room (:attempt/id entry)))
                                           _ (when-not (= id (:run/parent row)) (refuse! :foreign-result-attempt))
                                           projected (cond-> (assoc (select-keys entry [:candidate/agent-content-id :attempt/id
                                                                                        :attempt/content-id :repetition :reward :passed?])
                                                                    :environment/content-id (get-in entry [:environment :environment/content-id]))
                                                       (:spend entry) (assoc :spend (select-keys (:spend entry) [:microdollars :priced?])))]
                                       (if disclosure
                                         (merge projected (feedback/run-state row)
                                                (feedback/project disclosure (:run/job-experiment child) entry
                                                                  (episode/attempt-bounded control-room (:attempt/id entry))))
                                         (merge projected (feedback/run-state row)))))
                                   (take page-size (drop offset entries))))
                disclosure (assoc :public-feedback (feedback/public-declaration disclosure))
                (< (+ offset page-size) (count entries)) (assoc :next-offset (+ offset page-size))))
            (if (contains? #{:incomplete :unavailable} (:run/job-result-state child)) base
                (refuse! :missing-result-receipt)))]
      (let [result (bounded-page result)]
        ;; Deadline/cancel is sticky even if decoding and packing took time.
        (scope! ctx)
        (run/record-cause! run-id id)
        result))))
