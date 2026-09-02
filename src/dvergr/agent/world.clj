(ns dvergr.agent.world
  "Fork-backed work plane for one durable Run.

   A RunWorld keeps the parent Room as the control plane while exposing an
   isolated Room/context to the interpreter. Settlement is deliberately a
   second axis from execution: a completed program may be merged, retained for
   review, or discarded without rewriting its execution outcome."
  (:require [dvergr.discourse :as d]))

(def settlement-policies #{:automatic :review :discard :deferred})

(defrecord RunWorld [id parent work policy settlement])

(defn open!
  "Open an isolated work plane for `run-id`. The returned world is visible in
   the Room registry while open, so an explicitly reviewed world remains
   inspectable after its executor has quiesced."
  [parent run-id policy]
  (when-not (contains? settlement-policies policy)
    (throw (ex-info "Unknown Run settlement policy"
                    {:type ::invalid-settlement-policy
                     :policy policy
                     :allowed settlement-policies})))
  (let [work (d/fork-room parent {:isolation :ctx
                                  :fork-opts {:purpose :run
                                              :owner run-id
                                              ;; A hired agent receives the
                                              ;; durable substrate of its
                                              ;; parent world, but constructs
                                              ;; a fresh process-local runtime.
                                              ;; In particular, recursively
                                              ;; hiring from SCI must not try
                                              ;; to fork the interpreter that
                                              ;; is currently evaluating the
                                              ;; hire! call.
                                              :forkable-components #{}}
                                  ;; A Run world is an internal transaction, not
                                  ;; a child conversation. Nested agents enter
                                  ;; only through explicit hire/tool effects.
                                  :clone-participants? false})]
    (swap! (:meta work) assoc
           :run-world? true
           :run-id run-id
           :settlement-policy policy)
    (->RunWorld (:id work) parent work policy (atom nil))))

(defn- settle-once! [world f]
  (locking (:settlement world)
    (or @(:settlement world)
        (let [result (f)]
          (reset! (:settlement world) result)
          result))))

(defn settle!
  "Settle `world` after the executor has quiesced.

   Successful automatic work merges. Explicit review, and partial work that
   stopped at `:waiting`, remains registered for inspection. Discard policy,
   cancellation, and failure remove the work plane. An automatic merge failure
   is retained for review rather than destroying the only inspectable diff."
  [world execution-status]
  (settle-once!
   world
   ;; Deferred settlement is a generic two-phase gate. The work plane stays
   ;; inspectable, but cannot be merged or adopted until its host policy
   ;; explicitly releases it after verification/approval.
   (fn []
     (cond
       (#{:failed :cancelled} execution-status)
       (do ((if (= :deferred (:policy world))
              d/discard-deferred
              d/discard)
            (:work world))
           {:status :discarded :reason execution-status})

       (= :deferred (:policy world))
       {:status :deferred :reason :policy}

       (= :waiting execution-status)
       {:status :review :reason :execution-waiting}

       (= :discard (:policy world))
       (do (d/discard (:work world))
           {:status :discarded :reason :policy})

       (= :review (:policy world))
       {:status :review :reason :policy}

       :else
       (try
         (d/merge-room (:parent world) (:work world))
         {:status :merged}
         (catch Throwable t
           {:status :review
            :reason :automatic-merge-failed
            :error t}))))))
