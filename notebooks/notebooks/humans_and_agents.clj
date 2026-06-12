(ns notebooks.humans-and-agents
  "Live Clay notebook: humans + agents in a long-lived dvergr.discourse Room —
   addressed routing, background tasks, and the fork→propose→merge/discard
   lifecycle (the coding-agent isolation story).

   Reuses `examples/humans_and_agents.clj`'s `scripted-agent` helper and drives
   each demo inline (never calls the example's `run`/`-main`). All agents are
   scripted mocks — no API keys, no network; swapping in a real LLM agent
   doesn't change the primitives. Every demo binds the room's execution context
   once with `with-room`."
  (:refer-clojure :exclude [await])
  (:require [notebooks.support :refer [settle]]
            [datahike.api :as dh]
            [dvergr.discourse :as d :refer [with-room]]
            [dvergr.discourse.human :as human]
            [dvergr.discourse.background :as bg]
            [dvergr.discourse.proposals :as proposals]
            [dvergr.chat.schema :as schema]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.datahike :as ygg-dh]
            [scicloj.kindly.v4.kind :as kind]
            [humans-and-agents :as ha]))

;; # Humans, agents, and substrate forks
;;
;; This notebook walks four primitives that underpin coding-agent workflows
;; (the kind opencode / Claude Code subagents run on):
;;
;; 1. **Humans as discourse participants** (`dvergr.discourse.human`) — a
;;    human's id is a routing endpoint; an `on-receive` callback hands each
;;    inbox message to the embedding app's transport.
;; 2. **Background tasks with notifications**
;;    (`dvergr.discourse.background/spawn-task!`) — fire-and-forget a goal, get a
;;    typed `:task-complete` notification back.
;; 3. **Branch + merge via proposals** (`dvergr.discourse.proposals`) — fork the
;;    room, let an agent work in isolation, then **accept** (merge) or **reject**
;;    (discard) atomically.
;; 4. **Substrate isolation** — the worker's side effects (a datahike write) are
;;    real, but happen in a *branched copy*; the parent is untouched until accept.
;;
;; To capture what humans *receive* as data, we use a collecting `on-receive`.

(defn collecting-receive
  "Returns [received-atom on-receive-fn]. The fn conj's each inbound message
   (the bits we care about) onto the atom."
  []
  (let [received (atom [])]
    [received
     (fn [msg]
       (swap! received conj
              {:from         (:from msg)
               :content      (:content msg)
               :notification (some-> msg :metadata :notification/type)}))]))

;; ## Demo 1 — humans + agents, addressed routing
;;
;; Two humans (alice, bob) and one agent (sage). Each human asks sage a question
;; addressed directly; sage replies to each, addressed back.

(def demo1
  (let [room (d/room :nb-demo-1)
        [alice-rx alice-recv] (collecting-receive)
        [bob-rx bob-recv]     (collecting-receive)]
    (with-room room
      (let [alice (human/human-participant {:id :alice :on-receive alice-recv})
            bob   (human/human-participant {:id :bob :on-receive bob-recv})
            sage  (ha/scripted-agent :sage ["The weather is fine."
                                            "Cardinality is 3."])]
        (d/join room alice)
        (d/join room bob)
        (d/join room sage)
        (d/post! room (d/message :alice :sage "What's the weather?" nil nil))
        (d/post! room (d/message :bob :sage "Cardinality of a 3-set?" nil nil))
        (settle 200)
        (d/close-room! room)
        {:alice-received @alice-rx
         :bob-received   @bob-rx}))))

;; What alice received (addressed to her):

(kind/table (:alice-received demo1))

;; What bob received (addressed to him):

(kind/table (:bob-received demo1))

;; ## Demo 2 — background task + notification
;;
;; Alice fires a background task and goes about her day. When sage finishes,
;; alice gets a typed `:task-complete` notification in her inbox.

(def demo2
  (let [room (d/room :nb-demo-2)
        [alice-rx alice-recv] (collecting-receive)]
    (with-room room
      (let [alice (human/human-participant {:id :alice :on-receive alice-recv})
            sage  (ha/scripted-agent :sage ["Sources reviewed; PR ready."])]
        (d/join room alice)
        (d/join room sage)
        (bg/spawn-task! {:room   room
                         :agent  :sage
                         :task   {:content "Research X and prepare a PR"}
                         :notify :alice
                         :metadata {:project "X"}})
        (settle 200)
        (d/close-room! room)
        @alice-rx))))

;; Alice's inbox — note the `:notification` column carries `:task-complete`:

(kind/table demo2)

;; ## Demo 3 — propose → accept (fork → merge)
;;
;; Alice asks a coder for a feature; the coder works in a **forked** room and
;; posts a proposal. Alice accepts, and the fork merges into the parent room's
;; log. We use an in-memory datahike just to persist the proposal schema.

(def demo3
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room (d/room :nb-demo-3)]
    (with-room room
      (let [alice (human/human-participant {:id :alice :on-receive (fn [_])})
            ;; coder is NOT joined to the parent — propose! joins it into the
            ;; fork only.
            coder (ha/scripted-agent :coder ["Proposed: add :feature/x with default {}"])
            _     (d/join room alice)
            proposal @(proposals/propose! {:room   room
                                           :worker coder
                                           :goal   "design :feature/x"
                                           :conn   conn})
            outcome  (proposals/accept-proposal! room conn (:proposal/id proposal))
            result   {:summary         (:proposal/summary proposal)
                      :accept-outcome  outcome
                      :parent-log-size (count (d/log room))}]
        (d/close-room! room)
        (dh/release conn)
        result))))

(kind/pprint demo3)

;; ## Demo 4 — propose → reject (fork → discard)
;;
;; Same setup, but alice rejects. The fork is discarded and the parent room's
;; log is left unchanged.

(def demo4
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room (d/room :nb-demo-4)]
    (with-room room
      (let [coder (ha/scripted-agent :coder ["Proposed: rename :feature/x to :feature/y"])
            alice (human/human-participant {:id :alice :on-receive (fn [_])})
            _     (d/join room alice)
            proposal @(proposals/propose! {:room   room
                                           :worker coder
                                           :goal   "what should :feature/x be named?"
                                           :conn   conn})
            outcome  (proposals/reject-proposal! room conn (:proposal/id proposal))
            result   {:summary                   (:proposal/summary proposal)
                      :reject-outcome            outcome
                      :parent-log-size-unchanged (count (d/log room))}]
        (d/close-room! room)
        (dh/release conn)
        result))))

(kind/pprint demo4)

;; ## Demo 5 — substrate-isolated propose → accept (yggdrasil)
;;
;; The worker transacts into a yggdrasil-managed datahike inside its participant
;; spin. Until **accept**, the parent's datahike is unchanged — even though the
;; worker's transaction already ran in the branched copy. This is the
;; coding-agent isolation guarantee: the worker's side effects are real, they
;; just happen in a branched copy of the substrate.
;;
;; The notebook reliably demonstrates the **isolation** half:
;; `:parent-notes-before` is `0` — the worker's write is invisible to the parent
;; before accept. Whether `:parent-notes-after` surfaces the note as `1` depends
;; on the yggdrasil substrate-merge implementation on the classpath: with the
;; published maven `org.replikativ/yggdrasil` the datahike branch-merge does not
;; yet surface the row (it stays `0`); a `:local` checkout completes the merge.
;; The proposal is `:accepted` and the room **log** merges regardless.

(def demo5
  (let [;; A yggdrasil-managed datahike for the worker's knowledge base.
        worker-db-cfg {:store {:backend :memory :id (random-uuid)}
                       :keep-history? true}
        _ (dh/create-database worker-db-cfg)
        worker-conn (dh/connect worker-db-cfg)
        _ (dh/transact worker-conn
                       [{:db/ident :kb/topic :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                        {:db/ident :kb/note  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
        worker-system (ygg-dh/create worker-conn {:system-name "nb-worker-kb"})

        ;; Proposal-persistence DB (same as demos 3/4).
        prop-cfg {:store {:backend :memory :id (random-uuid)}}
        _ (dh/create-database prop-cfg)
        prop-conn (dh/connect prop-cfg)
        _ (schema/ensure-full-schema! prop-conn)

        room (d/room :nb-demo-5)
        ;; A worker that, when asked, transacts to its KB then replies. The
        ;; transact happens inside its participant spin — so inside whichever
        ;; ctx the fork uses (the factory arg), with the yggdrasil-managed
        ;; datahike branched automatically by spindel's PForkable extension.
        ;; This per-fork-ctx binding is NOT the room's ctx, so it stays explicit.
        make-worker (fn make-worker [ctx]
                      (binding [ec/*execution-context* ctx]
                        (d/participant
                         {:id :indexer
                          :ctx ctx
                          :on-message
                          (fn [_p msg]
                            (spin
                             (let [sys  (ygg/get-system "nb-worker-kb")
                                   conn (:conn sys)]
                               (dh/transact conn [{:kb/topic "X" :kb/note (:content msg)}])
                               {:to (:from msg) :content "Indexed."})))
                          :factory make-worker})))]
    (with-room room
      (let [alice  (human/human-participant {:id :alice :on-receive (fn [_])})
            worker (make-worker (:ctx room))
            _ (ygg/register! worker-system)
            _ (d/join room alice)
            proposal @(proposals/propose! {:room      room
                                           :worker    worker
                                           :goal      "index this note"
                                           :conn      prop-conn
                                           :isolation :ctx})
            count-before (count (dh/q '[:find ?n :where [_ :kb/note ?n]]
                                      @(:conn (ygg/get-system "nb-worker-kb"))))
            _ (proposals/accept-proposal! room prop-conn (:proposal/id proposal))
            count-after (count (dh/q '[:find ?n :where [_ :kb/note ?n]]
                                     @(:conn (ygg/get-system "nb-worker-kb"))))
            result {:summary             (:proposal/summary proposal)
                    :parent-notes-before count-before   ; expect 0 — fork isolated
                    :parent-notes-after  count-after}]  ; expect 1 — merged on accept
        (d/close-room! room)
        (dh/release worker-conn)
        (dh/release prop-conn)
        result))))

(kind/pprint demo5)

;; Before accept the parent KB has **0** notes — the worker's write lives only
;; in the isolated fork. See the note above on `:parent-notes-after` and the
;; substrate-merge implementation.

;; ---
;;
;; Source: [`examples/humans_and_agents.clj`](../../examples/humans_and_agents.clj).
