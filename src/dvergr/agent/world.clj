(ns dvergr.agent.world
  "Fork-backed work plane for one durable Run.

   A RunWorld keeps the parent Room as the control plane while exposing an
   isolated Room/context to the interpreter. Settlement is deliberately a
   second axis from execution: a completed program may be merged, retained for
   review, or discarded without rewriting its execution outcome."
  (:require [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.rooms.forks :as forks]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.effects.savepoint :as savepoint]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(def settlement-policies #{:automatic :review :discard :deferred})

(defrecord RunWorld [id parent work policy settlement])

(defn open!
  "Open an isolated work plane for `run-id`, a fork of `parent`. The returned
   world is visible in the Room registry while open, so an explicitly reviewed
   world remains inspectable after its executor has quiesced. `record-room`
   (default `parent`) is the Room that keeps the Run."
  ([parent run-id policy] (open! parent run-id policy parent))
  ([parent run-id policy record-room] (open! parent run-id policy record-room nil))
  ([parent run-id policy record-room {:keys [snapshots checkpoints]}]
   (when-not (contains? settlement-policies policy)
     (throw (ex-info "Unknown Run settlement policy"
                     {:type ::invalid-settlement-policy
                      :policy policy
                      :allowed settlement-policies})))
   (let [work (d/fork-room parent {:isolation :ctx
                                   :fork-opts {:purpose :run
                                               :owner run-id
                                               ;; resuming: the systems as the
                                               ;; savepoint recorded them
                                               :snapshots snapshots
                                               ;; A one-admission HOST origin, never
                                               ;; inheritable delegation authority.
                                               :checkpoints checkpoints
                                               ;; Hiring starts an independent computation,
                                               ;; not a continuation of the parent's session.
                                               :state-updates
                                               (savepoint/independent-fork-state
                                                (savepoint/derive-seed
                                                 (savepoint/seed (:ctx parent))
                                                 :dvergr/run run-id))
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
     ;; the world knows its Run: a resource authority spends from its wallet
     (resource/register-ledger-room! record-room)
     (binding [ec/*execution-context* (:ctx work)]
       (ec/swap-state! [:dvergr/run-id] (constantly run-id))
       ;; A Run owns its payer; never inherit a funded ancestor world's wallet.
       (ec/swap-state! [:dvergr/wallet]
                       (constantly {:kind :run :fork-id (:fork-id (:ctx work))
                                    :run-id run-id :wallet (resource/run-wallet-id run-id)
                                    :account (kontor/account-ref (resource/run-wallet-id run-id))
                                    :room-id (:id record-room)})))
     (swap! (:meta work) assoc
            :run-world? true
            :run-id run-id
           ;; Where the Run is recorded, which settlement updates: the
           ;; parent, or the control room of a Run whose world forks another.
            :record-room-id (:id record-room)
            :settlement-policy policy)
     (->RunWorld (:id work) parent work policy (atom nil)))))

(defn- settle-once! [world f]
  (locking (:settlement world)
    (or @(:settlement world)
        (let [result (f)]
          ;; another settlement in progress decides nothing yet: the next
          ;; settle! tries again
          (when-not (= :settlement-in-progress (:reason result))
            (reset! (:settlement world) result))
          result))))

(defn settlement-failure
  "Project a failed settlement from its canonical substrate authority, not the
   operation a lifecycle claim attempted. Only an open capability is ordinary
   review. Partial mutation requires recovery; a terminal substrate settlement
   remains terminal even if later cleanup/projection failed."
  [world reason error]
  (let [fork (:work world)
        handle (d/fork-handle fork)
        status (some-> handle ygg/fork-disposition :status)
        completed (:op (d/settlement fork))]
    (cond->
     (cond
       (= :incomplete status) {:status :recovery-required :reason :settlement-incomplete}
       (= :merged completed) {:status :merged :reason :settled-elsewhere}
       (= :discarded completed) {:status :discarded :reason :settled-elsewhere}
       (= :merged status) {:status :merged :reason :settlement-cleanup-failed}
       (= :discarded status) {:status :discarded :reason :settlement-cleanup-failed}
       (and handle (ygg/open-fork? handle)) {:status :review :reason reason}
       :else {:status :recovery-required :reason :settlement-authority-unavailable})
      error (assoc :error error))))

(defn- discarded
  "Discard the work plane, reporting `status`, or the settlement that holds
   or settled it (`:review` while that one is in progress)."
  [world status]
  (let [{:keys [outcome settlement in-progress?]} (d/discard-outcome (:work world))]
    (cond
      (not= :lost outcome) status
      (and in-progress?
           (not= :incomplete (some-> (:work world) d/fork-handle ygg/fork-disposition :status)))
      {:status :review :reason :settlement-in-progress}
      (and (not in-progress?) (#{:merged :discarded} (:op settlement))
           (not= :incomplete (some-> (:work world) d/fork-handle ygg/fork-disposition :status)))
      {:status (:op settlement) :reason :settled-elsewhere}
      :else (settlement-failure world :settlement-failed nil))))

(defn settle!
  "Settle `world` after the executor has quiesced.

   Successful automatic work merges. Explicit review, and partial work that
   stopped at `:waiting`, remains registered for inspection. Discard policy,
   cancellation, and failure remove the work plane. An automatic merge failure
   is retained for review only if authority is still open. Partial mutation is
   retained as `:recovery-required`, never advertised as ordinary review."
  [world execution-status]
  (settle-once!
   world
   ;; Deferred settlement is a generic two-phase gate. The work plane stays
   ;; inspectable, but cannot be merged or adopted until its host policy
   ;; explicitly releases it after verification/approval.
   (fn []
     (cond
       (#{:failed :cancelled} execution-status)
       (if (= :deferred (:policy world))
         (do (d/discard-deferred (:work world))
             {:status :discarded :reason execution-status})
         (discarded world {:status :discarded :reason execution-status}))

       (= :deferred (:policy world))
       {:status :deferred :reason :policy}

       (= :waiting execution-status)
       {:status :review :reason :execution-waiting}

       (= :discard (:policy world))
       (discarded world {:status :discarded :reason :policy})

       (= :review (:policy world))
       {:status :review :reason :policy}

       :else
       (try
         (d/merge-room (:parent world) (:work world)
                       {:guard (forks/workspace-merge-guard (:parent world) (:work world))})
         {:status :merged}
         (catch Throwable t
           (if (and (= :dvergr.discourse/already-settled (:type (ex-data t)))
                    (:in-progress? (ex-data t))
                    (not= :incomplete (some-> (:work world) d/fork-handle ygg/fork-disposition :status)))
             {:status :review :reason :settlement-in-progress}
             (settlement-failure world :automatic-merge-failed t))))))))
