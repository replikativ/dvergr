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
  (:require [dvergr.agent.run :as run]
            [dvergr.room.registry :as rreg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]
            [taoensso.telemere :as tel]))

(def kinds
  "The Run kinds a job has."
  #{:workflow :experiment})

(def max-wait-ms
  "Longest a status call blocks: below the shortest common client timeout."
  25000)

;; job id -> {:room :done}: where a job's Run is stored and the promise its
;; waiters block on. Process-local handles, never state: status is the Run's.
(defonce ^:private live (atom {}))

(defn- room-of [id]
  (when (uuid? id)
    (or (get-in @live [id :room])
        (some (fn [room]
                (when (and (:store room)
                           (binding [ec/*execution-context* (:ctx room)] (run/run room id)))
                  room))
              (rreg/list-rooms :where #(some? (:store %)))))))

(defn- job [room r]
  (when r
    {:id (:run/id r)
     :kind (:run/kind r)
     :room (:id room)
     :status (:run/status r)
     :started-at (:run/started-at r)
     :finished-at (:run/ended-at r)
     :error (:run/error r)
     :reason (:run/reason r)}))

(defn start!
  "Start `(work job-id)`, a Spin, in `room` as a job and return the job at
   once. The job is a durable Run of `kind` (one of `kinds`), stored before
   `work` is called; pass its id to the evaluations `work` starts as their
   `:parent-run`. `finish`, a fn of the Spin's value, runs before the Run
   completes."
  [room {:keys [kind]} work & {:keys [finish]}]
  (when-not (contains? kinds kind)
    (throw (ex-info "Unknown job kind" {:type ::unknown-kind :kind kind :kinds kinds})))
  (let [ctx (:ctx room)
        r (binding [ec/*execution-context* ctx]
            (run/start! room :dvergr (random-uuid) nil {:kind kind}))
        id (:run/id r)
        done (promise)]
    (swap! live assoc id {:room room :done done})
    (try
      (let [spin (binding [ec/*execution-context* ctx] (work id))]
        (run/register-cancel-hook! id ::spin
                                   #(binding [ec/*execution-context* ctx]
                                      (spin-core/cancel-spin! spin)))
        (future
          (let [[status error]
                (try
                  (let [v (binding [ec/*execution-context* ctx] @spin)]
                    (when finish (finish v))
                    [:completed nil])
                  (catch Throwable t
                    (if (run/cancel-requested? id)
                      [:cancelled nil]
                      (do (tel/log! {:level :warn :id ::failed
                                     :data {:job id :kind kind :error (ex-message t)}}
                                    "Job failed")
                          [:failed t]))))]
            (try
              (binding [ec/*execution-context* ctx]
                (run/finish! id status (cond-> {} error (assoc :error error))))
              (catch Throwable t
                (tel/log! {:level :error :id ::finish-failed
                           :data {:job id :status status :error (ex-message t)}}
                          "Job Run could not be finished")))
            (deliver done true)
            (swap! live dissoc id))))
      (catch Throwable t
        ;; `work` itself failed (validation, say): the Run must not stay open.
        (binding [ec/*execution-context* ctx]
          (run/finish! id :failed {:error t}))
        (deliver done true)
        (throw t)))
    (job room r)))

(defn status
  "The job `id` (its Run's id), waiting up to `wait-ms` (capped at
   `max-wait-ms`) for it to finish; nil for an unknown id. Also returns the
   room it is stored in (`:room-value`) for callers that read its results."
  ([id] (status id 0))
  ([id wait-ms]
   (when-let [room (room-of id)]
     (when (pos? (or wait-ms 0))
       (when-let [done (get-in @live [id :done])]
         (deref done (min max-wait-ms (long wait-ms)) nil)))
     (let [r (binding [ec/*execution-context* (:ctx room)] (run/run room id))]
       (some-> (job room r) (assoc :room-value room))))))

(defn cancel!
  "Stop the job `id`: request cancellation of its Run, which stops its work
   and settles it `:cancelled`. Returns the job, or nil for an unknown id. A
   finished job is returned unchanged."
  [id]
  (when (room-of id)
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
