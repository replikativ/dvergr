(ns dvergr.agent.run-savepoint-prefix-test
  "Provider-free probe of the proposed captured-prefix resume contract.
   Failure characterizes a conversation-scope/design gap; it does not decide
   whether an explicitly live-input resume policy should also be supported.
   This is a real stopped Run, not an OS-process crash/restart test."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as discourse]
            [dvergr.model.providers :as providers]
            [dvergr.room.store.datahike :as datahike-store]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private team
  (roster/make-agent
   (roster/make-roster {:id :prefix-probe-team})
   {:id :worker
    :tools #{}
    :model-policy {:provider :codex-subscription :model "codex-subscription-sol"}
    :program {:kind :llm :max-model-steps 4 :auto-compact? false}}))

(defn- prefix [messages]
  (mapv #(select-keys % [:message/id :message/role :message/content :message/created-at])
        messages))

(defn- with-owned-room [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (let [room (discourse/make-room
                  {:id (keyword (str "savepoint-prefix-" (random-uuid)))
                   :store (datahike-store/make conn)})]
        (try
          (binding [ec/*execution-context* (:ctx room)]
            (f room conn))
          (finally
            ;; This real close drains every owned Run/worker before releasing
            ;; its exact fresh memory store. A refused close intentionally
            ;; prevents release/delete; a diagnostic deadline is not cleanup.
            (discourse/close-room! room)
            (dh/release conn)
            (assert (and (= :memory (get-in cfg [:store :backend]))
                         (uuid? (get-in cfg [:store :id]))))
            (dh/delete-database cfg)))))))

(deftest a-stopped-run-resumes-the-captured-prefix-not-a-later-chat-tail
  (let [phase (atom :initial)
        calls (atom [])
        captured-prefix (atom nil)
        resumed-input (atom nil)
        clock (atom (System/currentTimeMillis))
        create-message! chat-schema/create-message-entity!]
    ;; The real message factory still creates IDs and validates message/tool
    ;; schemas. Only timestamps are made strictly increasing: load-messages
    ;; currently sorts by Date alone, and timestamp ties must not obscure this
    ;; distinct late-input probe. No sleeps, provider, persistence, savepoint,
    ;; hydrate, Run admission or world settlement implementation is substituted.
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  chat-schema/create-message-entity!
                  (fn [conn opts]
                    (assoc (create-message! conn opts)
                           :message/created-at (java.util.Date. (long (swap! clock inc)))))
                  chat-agent/run-agent-turn!
                  (fn [chat-ctx opts]
                    (let [step (:turn-number opts)]
                      (swap! calls conj [@phase step])
                      (case [@phase step]
                        [:initial 0]
                        (do (chat-context/add-message! chat-ctx
                                                       {:role :assistant :content "checkpoint prefix"})
                            :continue)
                        [:initial 1]
                        (do (reset! captured-prefix (prefix (chat-context/get-messages chat-ctx)))
                            (throw (ex-info "intentional stop after the recorded turn" {})))
                        [:resumed 1]
                        (do (reset! resumed-input (prefix (chat-context/get-messages chat-ctx)))
                            (chat-context/add-message! chat-ctx
                                                       {:role :assistant :content "resumed without a provider"})
                            :complete)
                        (throw (ex-info "Unexpected model boundary in prefix probe"
                                        {:phase @phase :step step})))))]
      (with-owned-room
        (fn [room conn]
          (let [old-handle (program/hire! room team :worker {:task "prefix probe"})
                stopped @old-handle
                old-id (program/run-id old-handle)
                old (run/run room old-id)
                descriptor (run/savepoint old)
                descriptor-edn (:run/savepoint old)
                old-chat (:run/chat-id old)
                original (prefix (chat-context/load-messages conn old-chat))]
            (is (= :failed (:run/status stopped) (:run/status old)))
            (is (empty? (run/active-runs (:id room)))
                "The original Run physically returned before editing its persisted chat")
            (is (= [[:initial 0] [:initial 1]] @calls))
            (is (= 0 (get-in descriptor [:savepoint/payload :step])))
            (is (uuid? (:savepoint/id descriptor)))
            (is (seq @captured-prefix))
            (is (= @captured-prefix original)
                "The incoming prefix at the stopped step equals the durable pre-append prefix")
            (is (= [:system :user :assistant] (mapv :message/role original)))
            (let [last-ms (apply max (map #(.getTime ^java.util.Date (:message/created-at %)) original))
                  late (assoc (chat-schema/create-message-entity!
                               conn {:chat-id old-chat :role :user :content "arrived after the checkpoint"})
                              :message/created-at (java.util.Date. (long (inc last-ms))))]
              (swap! clock max (inc last-ms))
              (dh/transact conn [late])
              (is (= (conj original (first (prefix [late])))
                     (prefix (chat-context/load-messages conn old-chat)))
                  "The extra row is real, ordered after the old prefix, and readable")
              (is (= descriptor-edn (:run/savepoint (run/run room old-id))))
              (is (= descriptor (run/savepoint (run/run room old-id))))
              (reset! phase :resumed)
              (let [successor (program/resume! room room old-id)
                    result @successor
                    new-id (program/run-id successor)]
                (is (= :completed (:run/status result)))
                (is (= [[:initial 0] [:initial 1] [:resumed 1]] @calls))
                ;; Intentionally strict: retain a real RED result if the current
                ;; API silently includes the late row. Do not xfail or replace
                ;; this with a tail-inclusive assertion before choosing policy.
                (is (= @captured-prefix @resumed-input)
                    "Proposed contract: checkpoint resume restores precisely its captured prefix")
                (is (not-any? #(= (:message/id late) (:message/id %)) @resumed-input))
                (is (contains? (:run/caused-by (run/run room new-id)) old-id))
                (is (= new-id (:run/resumed-by (run/run room old-id))))
                (is (empty? (run/active-runs (:id room))))
                (is (thrown? Exception (program/resume! room room old-id)))))))))))
