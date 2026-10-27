(ns dvergr.laws.live-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dvergr.adapters.live-test :as live]
            [dvergr.discourse :as discourse]
            [dvergr.discourse-support :as t]
            [dvergr.participant :as participant]
            [dvergr.store :as store]
            [org.replikativ.spindel.spin.sync :as sync]))

(deftest d2-session-claim-command-model
  "D2: generated duplicate offers, overlapping rules and unsubscribe/resubscribe retain one claim before handler entry; rejoin uses a fresh seen set."
  (t/with-room
    (fn [i r v]
      (is (:pass?
           (tc/quick-check
            12
            (prop/for-all [commands (gen/vector (gen/tuple (gen/elements [:offer :subscribe :unsubscribe])
                                                           (gen/choose 0 3)) 1 12)]
                          (let [target (random-uuid) calls (atom []) rules (atom [])
                                session (participant/join! i v (t/author i r target)
                                                           {:handler (fn [_ e _]
                                                                       (swap! calls conj (:message/id e)) nil)})
                                a (t/author i r)
                                messages (mapv (fn [_] (:envelope (discourse/post! i v a
                                                                                   (assoc (t/message) :recipients {:participants #{target}}))))
                                               (range 4))
                                ids (set (map :message/id messages))]
                            (doseq [[command slot] commands]
                              (case command
                                :offer (sync/post! (:mailbox r) (messages slot))
                                :subscribe (swap! rules conj (participant/subscribe! i session {:tags #{:test} :direct? true}))
                                :unsubscribe (when-let [id (peek @rules)]
                                               (participant/unsubscribe! i session id) (swap! rules pop))))
                            (let [settled (live/eventually #(= ids (set @calls)))
                                  unique (= (count ids) (count @calls))]
                              (t/await! (participant/leave! i session))
                              (let [replacement (participant/join! i v (t/author i r target)
                                                                   {:handler (fn [_ e _] (swap! calls conj (:message/id e)) nil)})]
                                (sync/post! (:mailbox r) (first messages))
                                (let [fresh (live/eventually #(= 5 (count @calls)))]
                                  (t/await! (participant/leave! i replacement))
                                  (and settled unique fresh (not= (:id session) (:id replacement))))))))))))))

(deftest d2-capacity-ends-session-without-forgetting-claims
  "D2: seen-set capacity ends the incarnation instead of evicting an identity and allowing duplicate handler entry."
  (t/with-room
    (fn [i r v]
      (let [calls (atom []) session (participant/join! i v (t/author i r)
                                                       {:capacity 1 :handler (fn [_ e _] (swap! calls conj (:message/id e)) nil)})
            a (t/author i r) one (discourse/post! i v a (t/message))]
        (is (live/eventually #(= 1 (count @calls))))
        (discourse/post! i v a (t/message))
        (is (= {:status :closed} (t/await! (:completion session))))
        (sync/post! (:mailbox r) (:envelope one))
        (is (= #{(get-in one [:envelope :message/id])} @(:seen session)))
        (is (= 1 (count @calls)))))))

(deftest d3-first-terminal-control-commit-command-model
  "D3: generated completion permutations retain the first control commit under reply, timeout, cancellation, leave and duplicate completion."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r)
            session (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})]
        (is (:pass?
             (tc/quick-check
              15
              (prop/for-all [statuses (gen/vector (gen/elements [:reply :timeout :cancelled :leave]) 1 10)]
                            (let [asked (discourse/ask i v a (live/request target (+ (System/currentTimeMillis) 60000)))
                                  reply (:envelope (discourse/post! i v a (assoc (t/message)
                                                                                 :ask/id (:id asked)
                                                                                 :reply-to (get-in asked [:post :envelope :message/id]))))
                                  candidates (mapv #(case %
                                                      :reply {:status :reply :message/id (:message/id reply)}
                                                      :timeout {:status :timeout}
                                                      {:status % :reason :model}) statuses)
                                  results (mapv #(discourse/finish! i r (:id asked) %) candidates)]
                              (and (every? #{(first candidates)} results)
                                   (= (first candidates) (t/await! (:result asked)))
                                   (= (first candidates) (:ask/terminal (store/ask-record (:control i) (:id asked))))))))))
        (t/await! (participant/leave! i session))))))
