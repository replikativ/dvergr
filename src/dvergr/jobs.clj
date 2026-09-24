(ns dvergr.jobs
  "Long operations as jobs the daemon owns: started by one call, then polled,
   waited on or cancelled by later calls, from any client.

   A client's single call cannot outlast its timeout (Codex allows 60 s by
   default, Claude Code backgrounds a call after 2 minutes), while a set of
   agent attempts takes minutes. So such an op starts a job and returns its
   id; `status` long-polls it. Jobs live in this process: they survive a
   client's restart, not the daemon's (their Attempts are durable anyway).

   A job is `{:id :op :room :status :started-at :finished-at :result :error}`
   with `:status` one of :running, :completed, :failed, :cancelled."
  (:require [taoensso.telemere :as tel]))

(defonce ^:private registry (atom {}))

(def max-finished
  "Finished jobs kept for later status calls; the oldest go first."
  200)

(def max-wait-ms
  "Longest a status call blocks: below the shortest common client timeout."
  25000)

(defn- now [] (System/currentTimeMillis))

(defn- prune [jobs]
  (let [finished (->> (vals jobs)
                      (remove #(= :running (:status %)))
                      (sort-by :finished-at))
        excess (- (count finished) max-finished)]
    (if (pos? excess)
      (apply dissoc jobs (map :id (take excess finished)))
      jobs)))

(defn- public [job]
  (dissoc job ::done ::cancel))

(defn- finish! [id status k v]
  (let [[old _] (swap-vals! registry
                            (fn [jobs]
                              (if (= :running (get-in jobs [id :status]))
                                (prune (update jobs id assoc :status status k v :finished-at (now)))
                                jobs)))]
    (when-let [done (get-in old [id ::done])]
      (deliver done true))))

(defn start!
  "Run `thunk` (a blocking fn of no arguments) as a job and return the job at
   once. `cancel` (a fn of no arguments, optional) is how `cancel!` stops the
   work; the job is marked cancelled either way. `info` is kept on the job
   (`:op`, `:room`, …)."
  [info thunk & {:keys [cancel]}]
  (let [id (str (random-uuid))
        job (merge info {:id id :status :running :started-at (now)
                         ::done (promise) ::cancel cancel})]
    (swap! registry assoc id job)
    (future
      (try
        (finish! id :completed :result (thunk))
        (catch Throwable t
          (tel/log! {:level :warn :id ::failed :data {:job id :op (:op info) :error (ex-message t)}}
                    "Job failed")
          (finish! id :failed :error (or (ex-message t) (str t))))))
    (public job)))

(defn status
  "The job `id`, waiting up to `wait-ms` (capped at `max-wait-ms`) for it to
   finish; nil for an unknown id."
  ([id] (status id 0))
  ([id wait-ms]
   (when-let [job (get @registry id)]
     (when (and (pos? (or wait-ms 0)) (= :running (:status job)))
       (deref (::done job) (min max-wait-ms (long wait-ms)) nil))
     (some-> (get @registry id) public))))

(defn cancel!
  "Stop the job `id`: run its cancel fn and mark it cancelled. Returns the job,
   or nil for an unknown id. A finished job is returned unchanged."
  [id]
  (when-let [job (get @registry id)]
    (when (= :running (:status job))
      ;; Cancelled first: stopping the work makes the job's own thread fail
      ;; ("Spin cancelled"), and whichever finishes first decides the status.
      (finish! id :cancelled :error "cancelled")
      (when-let [c (::cancel job)]
        (try (c) (catch Throwable t
                   (tel/log! {:level :warn :id ::cancel-failed :data {:job id :error (ex-message t)}}
                             "Job cancel fn failed")))))
    (status id)))

(defn jobs
  "Jobs, newest first, optionally only those with `:room` = `room`."
  ([] (jobs nil))
  ([room]
   (->> (vals @registry)
        (filter #(or (nil? room) (= room (:room %))))
        (sort-by :started-at >)
        (mapv public))))
