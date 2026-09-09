(ns dvergr.model.admission-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.agent.run :as run]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as chat]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.provider :as provider]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as datahike-store])
  (:import [java.io ByteArrayInputStream IOException]
           [java.util.concurrent CancellationException CountDownLatch TimeUnit]))

(defn- with-wallet [units f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        id (keyword (str "admission-" (random-uuid)))
        chat-id (random-uuid)
        run-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (schema/create-chat-entity {:id chat-id :title "admission"})
                                {:room/slug (store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      (let [room (d/make-room {:id id :store (datahike-store/make conn)})
            trigger (d/message :test :_runs "admission" nil {:role :user})]
        (try
          (resource/install-unit! room {:symbol resource/model-dispatches
                                        :name "Native model dispatch admission" :precision 0})
          (resource/mint! room {:id (random-uuid) :resources {resource/model-dispatches units}})
          (d/post! room trigger)
          (run/start! room :test trigger nil {:id run-id})
          (resource/allocate-run! room run-id nil {resource/model-dispatches units})
          (binding [resource/*model-scope* (resource/model-scope room run-id)]
            (f room run-id))
          (finally
            (run/finish! run-id :completed)
            (d/close-room! room)
            (dh/release conn)))))))

(def credentials
  (gateway/static-credentials :test {} #{"https://model.test"}))
(def request {:url "https://model.test/responses" :method :post :credentials credentials})

(deftest concurrent-dispatches-cannot-spend-the-last-unit-twice
  (with-wallet
    1M
    (fn [room run-id]
      (let [gate (CountDownLatch. 1) called (atom 0)]
        (binding [gateway/*request-fn* (fn [_] (swap! called inc) {:status 200})]
          (let [jobs (mapv (fn [_] (future
                                     (.await gate 5 TimeUnit/SECONDS)
                                     (try (gateway/request! request) :ok
                                          (catch Exception _ :rejected)))) (range 2))]
            (.countDown gate)
            (is (= {:ok 1 :rejected 1} (frequencies (map #(deref % 10000 :timeout) jobs))))
            (is (= 1 @called))
            (is (= {} (resource/run-balance room run-id)))
            (is (some? (resource/model-scope room run-id)) "empty wallet stays governed")
            (is (thrown? Exception (gateway/request! request)))
            (is (= 1 @called))))))))

(deftest failed-dispatch-is-not-refunded
  (with-wallet
    1M
    (fn [room run-id]
      (let [called (atom 0)]
        (binding [gateway/*request-fn* (fn [_] (swap! called inc) (throw (IOException. "unknown outcome")))]
          (is (thrown? IOException (gateway/request! request)))
          (is (= {} (resource/run-balance room run-id)))
          (is (thrown? Exception (gateway/request! request)))
          (is (= 1 @called)))))))

(deftest cancelled-before-admission-does-not-spend
  (with-wallet
    1M
    (fn [room run-id]
      (binding [resource/*model-scope* (assoc resource/*model-scope* :cancel? (constantly true))
                gateway/*request-fn* (fn [_] (throw (AssertionError. "Should not dispatch")))]
        (is (thrown? CancellationException (gateway/request! request)))
        (is (= {resource/model-dispatches 1M} (resource/run-balance room run-id)))))))

(deftest authentication-retry-needs-another-admission
  (doseq [units [1M 2M]]
    (with-wallet
      units
      (fn [room run-id]
        (let [closed? (atom false) called (atom 0)
              credentials (reify gateway/Credentials
                            (credential-kind [_] :test)
                            (allowed-origins [_] #{"https://model.test"})
                            (resolve-auth! [_ _] {:headers {} :version :test})
                            (recover-auth! [_ _ _] true))]
          (binding [gateway/*request-fn*
                    (fn [_]
                      (if (= 1 (swap! called inc))
                        {:status 401 :body (proxy [ByteArrayInputStream] [(byte-array 0)]
                                             (close [] (reset! closed? true)))}
                        {:status 200}))]
            (if (= 1M units)
              (is (thrown? Exception (gateway/request! (assoc request :credentials credentials))))
              (is (= 200 (:status (gateway/request! (assoc request :credentials credentials))))))
            (is @closed?)
            (is (= (long units) @called))
            (is (= {} (resource/run-balance room run-id)))))))))

(deftest direct-providers-cannot-hide-dispatches-from-a-governed-run
  (let [called (atom false)
        direct (reify provider/DirectChat
                 (direct-chat [_ _ _] (reset! called true)))]
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  registry/resolve-alias identity
                  registry/get-model! (constantly {:provider :test})
                  registry/get-quirk (constantly nil)
                  providers/get-provider! (constantly direct)]
      (binding [resource/*model-scope* {:run-id (random-uuid)}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"native HTTP provider"
                              (chat/chat [] {:model "test"})))
        (is (false? @called))))))

(deftest redirects-cannot-hide-extra-dispatches
  (with-wallet
    1M
    (fn [room run-id]
      (let [closed? (atom false) called (atom 0)
            redirecting (-> (java.net.http.HttpClient/newBuilder)
                            (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
                            .build)]
        (binding [gateway/*request-fn*
                  (fn [request]
                    (swap! called inc)
                    (is (= java.net.http.HttpClient$Redirect/NEVER
                           (.followRedirects ^java.net.http.HttpClient (:http-client request))))
                    {:status 302 :body (proxy [ByteArrayInputStream] [(byte-array 0)]
                                         (close [] (reset! closed? true)))})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-redirecting"
                                (gateway/request! (assoc request :http-client redirecting))))
          (is (= {resource/model-dispatches 1M} (resource/run-balance room run-id)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not follow redirects"
                                (gateway/request! request)))
          (is @closed?)
          (is (= 1 @called))
          (is (= {} (resource/run-balance room run-id))))))))

(deftest transient-provider-retries-each-consume-admission
  (with-wallet
    2M
    (fn [room run-id]
      (let [called (atom 0)]
        (binding [gateway/*request-fn* (fn [_] (swap! called inc)
                                         {:status 503 :body (ByteArrayInputStream.
                                                             (.getBytes "{}" "UTF-8"))})]
          (with-redefs-fn {#'chat/calculate-backoff (constantly 0)}
            #(is (thrown? Exception
                          (chat/stream-chat (openai/create {:api-key "test"
                                                            :base-url "https://model.test"})
                                            {} [] {:model "stub"}))))
          (is (= 2 @called))
          (is (= {} (resource/run-balance room run-id))))))))
