(ns dvergr.ops-coverage-test
  "The ops spec covers what the durable stores can do.

   datahike and proximum derive their APIs from a specification; dvergr derives
   its bindings (MCP, JSON API, web) from `dvergr.ops/specification`, but the
   capabilities underneath are ordinary functions. Runs, Attempts and Scorecards
   were persisted for weeks with no op, so no binding could see them. This test
   makes that impossible to repeat: every method of the durable store protocols
   is classified here, as the op that reads it, as written only by the runtime
   (never directly by a binding), or as internal. A new protocol method fails
   the test until it is classified."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.ops :as ops]
            [dvergr.room.store :as store]))

(def ^:private coverage
  {;; rooms and messages
   :-store-room!          {:written-by #{:room/create}}
   :-load-room            {:read-by #{:room/detail}}
   :-delete-room!         {:written-by #{:room/delete}}
   :-list-rooms           {:read-by #{:room/list}}
   :-store-message!       {:written-by #{:room/post}}
   :-message-thread-root  :internal
   :-list-messages        {:read-by #{:room/messages}}
   ;; runs
   :-store-run!           :runtime
   :-load-run             {:read-by #{:run/detail}}
   :-list-runs            {:read-by #{:run/list}}
   ;; wallets
   :-open-resource-wallet!      :runtime
   :-install-resource-unit!     :runtime
   :-allocate-resource-wallet!  :runtime
   :-transfer-resources!        :runtime
   :-resource-balance     {:read-by #{:room/wallet}}
   :-resource-receipt     :internal
   ;; attention (routing state, not a user-facing record)
   :-store-attention!     :runtime
   :-list-attention       :internal
   ;; evaluation
   :-store-attempt!       {:written-by #{:workflow/attempt}}
   :-load-attempt         {:read-by #{:attempt/detail}}
   :-list-attempts        {:read-by #{:attempt/list}}
   :-store-scorecard!     :runtime
   :-load-scorecard       {:read-by #{:scorecard/detail}}
   :-list-scorecards      {:read-by #{:scorecard/list}}})

(def ^:private protocols
  [store/PRoomStore store/PResourceStore store/PAttentionStore
   store/PAttemptStore store/PScorecardStore])

(defn- methods-of [protocol]
  (set (map (comp keyword name) (keys (:sigs protocol)))))

(deftest every-store-method-is-classified
  (let [all (reduce into #{} (map methods-of protocols))]
    (is (= #{} (clojure.set/difference all (set (keys coverage))))
        "a store protocol method without a classification: give it an op, or say why not")
    (is (= #{} (clojure.set/difference (set (keys coverage)) all))
        "a classification for a method that no longer exists")))

(deftest the-ops-named-exist-with-the-right-kind
  (doseq [[method c] coverage
          :when (map? c)
          [k kind] [[:read-by :read] [:written-by :write]]
          op (get c k)]
    (testing (str method " -> " op)
      (is (contains? ops/specification op))
      (is (= kind (:kind (ops/specification op)))))))
