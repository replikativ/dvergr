(ns dvergr.model.reservation-test
  "A funded model dispatch reserves a bound of its cost before it leaves and
   is settled once (doc/design/runs-as-worlds.md, Budgets as a tree)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.run :as run]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.chat :as chat]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.provider :as provider]
            [dvergr.model.registry :as registry]
            [dvergr.model.api.anthropic]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as datahike-store])
  (:import [java.io ByteArrayInputStream IOException]))

(defn- with-funded-run
  "A Run whose allocation carries `dispatches` admissions and `md`
   microdollars, with its spend wallet and admissions bound."
  [dispatches md f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        id (keyword (str "reservation-" (random-uuid)))
        chat-id (random-uuid)
        run-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (schema/create-chat-entity {:id chat-id :title "reservation"})
                                {:room/slug (store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      (let [room (d/make-room {:id id :store (datahike-store/make conn)})
            trigger (d/message :test :_runs "reservation" nil {:role :user})]
        (try
          (resource/install-unit! room {:symbol resource/model-dispatches
                                        :name "Native model dispatch admission" :precision 0})
          (resource/mint! room {:id (random-uuid) :resources {resource/model-dispatches dispatches
                                                              resource/microdollars md}})
          (d/post! room trigger)
          (run/start! room :test trigger nil {:id run-id})
          (resource/allocate-run! room run-id nil {resource/model-dispatches dispatches
                                                   resource/microdollars md})
          (binding [resource/*model-scope* (resource/model-scope room run-id)
                    resource/*spend-wallet* (resource/spending (resource/spend-wallet room run-id)
                                                               [:test run-id])]
            (f room run-id))
          (finally
            (run/finish! run-id :completed)
            (d/close-room! room)
            (dh/release conn)))))))

(def ^:private request
  {:url "https://model.test/v1/chat/completions" :method :post
   :credentials (gateway/static-credentials :test {} #{"https://model.test"})})

(defn- left [room run-id] (resource/wallet-microdollars room run-id))

(deftest the-answer-settles-its-reservation-and-the-charge-draws-on-it
  (with-funded-run
    2M 1000000M
    (fn [room run-id]
      (binding [gateway/*request-fn* (fn [_] {:status 200})
                resource/*dispatches* (atom [])
                resource/*dispatch-bound* 5000M]
        (gateway/request! request)
        (is (= 995000M (left room run-id)) "the bound is reserved before the answer")
        (resource/settle-dispatches! 1200M)
        (is (= 998800M (left room run-id)) "the rest returns once the usage is known")
        (testing "the charge that follows from the same usage debits nothing again"
          (is (= 1200M (:charged (resource/charge-spend! 1200M))))
          (is (= 998800M (left room run-id))))
        (testing "settling again changes nothing"
          (resource/settle-dispatches! 1200M)
          (is (= 998800M (left room run-id))))
        (testing "the same charge replayed (its scope charged again) debits nothing"
          (binding [resource/*spend-wallet* (resource/spending (resource/spend-wallet room run-id)
                                                               [:test run-id])]
            (is (= 1200M (:charged (resource/charge-spend! 1200M)))))
          (is (= 998800M (left room run-id))))
        (testing "a further charge, with nothing held, comes from the wallet"
          (is (= 100M (:charged (resource/charge-spend! 100M))))
          (is (= 998700M (left room run-id))))))))

(deftest an-ambiguous-dispatch-keeps-its-reservation
  (with-funded-run
    2M 1000000M
    (fn [room run-id]
      (binding [gateway/*request-fn* (fn [_] (throw (IOException. "broken")))
                resource/*dispatches* (atom [])
                resource/*dispatch-bound* 5000M]
        (is (thrown? IOException (gateway/request! request)))
        (resource/settle-dispatches! nil)
        (is (= 995000M (left room run-id)))))))

(deftest a-refused-dispatch-returns-its-reservation-at-once
  (with-funded-run
    2M 5000M
    (fn [room run-id]
      (let [credentials (reify gateway/Credentials
                          (credential-kind [_] :test)
                          (allowed-origins [_] #{"https://model.test"})
                          (resolve-auth! [_ _] {:headers {} :version :test})
                          (recover-auth! [_ _ _] true))
            called (atom 0)]
        (binding [gateway/*request-fn* (fn [_] (if (= 1 (swap! called inc)) {:status 401} {:status 200}))
                  resource/*dispatches* (atom [])
                  resource/*dispatch-bound* 5000M]
          (is (= 200 (:status (gateway/request! (assoc request :credentials credentials))))
              "a wallet that covers one bound can retry after a refusal")
          (is (= 0M (left room run-id)))
          (resource/settle-dispatches! 300M)
          (is (= 4700M (left room run-id))))))))

(deftest a-dispatch-without-a-bound-or-cover-does-not-leave
  (doseq [[why md bound] [["no bound" 1000000M nil] ["the wallet cannot cover it" 1000M 5000M]]]
    (testing why
      (with-funded-run
        1M md
        (fn [room run-id]
          (let [called (atom 0)]
            (binding [gateway/*request-fn* (fn [_] (swap! called inc) {:status 200})
                      resource/*dispatches* (atom [])
                      resource/*dispatch-bound* bound]
              (is (thrown? Exception (gateway/request! request)))
              (is (zero? @called))
              (is (= {resource/model-dispatches 1M resource/microdollars md}
                     (resource/run-balance room run-id))))))))))

(deftest a-funded-dispatch-must-be-settled-by-its-model-call
  (with-funded-run
    1M 1000000M
    (fn [_ _]
      (binding [gateway/*request-fn* (fn [_] {:status 200})
                resource/*dispatch-bound* 5000M]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"settles it"
                              (gateway/request! request)))))))

(deftest remote-content-has-no-bound
  (let [model {:pricing {:input 1.0 :output 2.0}}]
    (is (some? (resource/dispatch-bound model {:messages [{:content "hi"}]} 100 10)))
    (is (nil? (resource/dispatch-bound model {:messages [{:content [{:type "image_url" :image_url {:url "https://example.org/a.png"}}]}]} 100 10)))
    (is (nil? (resource/dispatch-bound model {:messages [{:content [{:type "image" :source {:type "url" :url "https://example.org/a.png"}}]}]} 100 10)))
    (is (nil? (resource/dispatch-bound model {:input [{:type "input_file" :file_id "file-1"}]} 100 10)))
    (is (nil? (resource/dispatch-bound model {:messages []} 100 nil)) "no output limit")
    (is (nil? (resource/dispatch-bound {:pricing {:input 1.0}} {} 100 10)) "no output price")
    (testing "an inline image is bounded by a per-image allowance, not its bytes"
      (is (< (resource/dispatch-bound model {:messages [{:content "x"}]} 100 10)
             (resource/dispatch-bound model {:messages [{:content [{:type "image_url" :image_url {:url "data:image/png;base64,AA"}}]}]} 100 10))))))

(deftest a-stream-without-final-usage-keeps-its-reservation
  (with-funded-run
    3M 10000000M
    (fn [room run-id]
      (let [sse (str "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")
            model "gpt-5.6-luna"
            spent (atom nil)]
        (binding [gateway/*request-fn*
                  (fn [_] {:status 200 :body (ByteArrayInputStream. (.getBytes sse "UTF-8"))})]
          (with-redefs-fn {#'dvergr.model.providers/get-provider!
                           (constantly (openai/create {:api-key "test" :base-url "https://model.test"}))
                           #'dvergr.model.providers/ensure-initialized! (constantly nil)
                           #'resource/settle-dispatches!
                           (let [settle resource/settle-dispatches!]
                             (fn [cost] (reset! spent cost) (settle cost)))}
            #(chat/chat [{:role "user" :content "hello"}] {:model model :max-tokens 100})))
        (is (nil? @spent) "a truncated stream reports no final usage")
        (is (< (left room run-id) 10000000M) "its reservation is consumed whole")))))

(deftest a-model-call-reserves-from-its-request-and-settles-at-the-usage-price
  (with-funded-run
    3M 10000000M
    (fn [room run-id]
      (let [sse (str "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                     "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                     "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}\n\n"
                     "data: [DONE]\n\n")
            seen (atom nil)
            model "gpt-5.6-luna"
            {:keys [input output]} (:pricing (registry/get-model model))]
        (binding [gateway/*request-fn*
                  (fn [_] (reset! seen (left room run-id))
                    {:status 200 :body (ByteArrayInputStream. (.getBytes sse "UTF-8"))})]
          (with-redefs-fn {#'dvergr.model.providers/get-provider!
                           (constantly (openai/create {:api-key "test" :base-url "https://model.test"}))
                           #'dvergr.model.providers/ensure-initialized! (constantly nil)}
            #(let [response (chat/chat [{:role "user" :content "hello"}] {:model model :max-tokens 100})]
               (is (= "hi" (:content response)))
               (is (< @seen 10000000M) "the bound was reserved before the dispatch left")
               (is (= (- 10000000M (bigdec (+ (* 100 input) (* 10 output))))
                      (left room run-id))
                   "settled at the usage's price, nothing else kept"))))))))

(deftest anthropic-usage-counts-cached-input-and-is-final-with-the-stop-reason
  (let [p (dvergr.model.api.anthropic/create {:api-key "test"})
        state (provider/create-accumulator p nil)
        state (provider/accumulate-event p state "message_start"
                                         {:message {:id "m" :model "claude"
                                                    :usage {:input_tokens 10
                                                            :cache_read_input_tokens 1000
                                                            :cache_creation_input_tokens 200
                                                            :output_tokens 1}}} nil)
        started (provider/extract-response p state)
        state (provider/accumulate-event p state "message_delta"
                                         {:delta {:stop_reason "end_turn"} :usage {:output_tokens 42}} nil)
        done (provider/extract-response p state)]
    (is (false? (:usage-final? started)) "the opening usage is not final")
    (is (= {:input-tokens 1210 :output-tokens 42 :cache-read-tokens 1000 :cache-creation-tokens 200}
           (:usage done)))
    (is (true? (:usage-final? done)))))
