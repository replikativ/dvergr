(ns dvergr.model.chat-stream-test
  "A response stream that goes quiet is closed and asked again, not waited on
   until something above gives up."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.chat :as chat]
            [dvergr.model.provider :as p]
            [dvergr.model.gateway :as gateway])
  (:import [java.io ByteArrayInputStream]
           [java.net InetSocketAddress]
           [java.net.http HttpTimeoutException]
           [com.sun.net.httpserver HttpServer HttpHandler]))

(def ^:private events
  (str "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
       "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"model\":\"m\",\"usage\":{}}}\n\n"))

(defn- provider []
  (reify
    p/LLMProvider
    (provider-id [_] :test-stream)
    (api-type [_] :openai-responses)
    (build-request [_ _ _] {:url "http://test.invalid" :headers {} :body {}})
    (create-accumulator [_ _] {:content ""})
    (accumulate-event [_ state type event _]
      (if (= "response.output_text.delta" type) (update state :content str (:delta event)) state))
    (extract-response [_ state] {:content (:content state) :stop-reason :end-turn})))

(deftest a-stalled-stream-is-asked-again
  (let [calls (atom 0)
        silent (fn []
                 ;; headers arrived, then nothing; closing it aborts the
                 ;; blocked read, as closing an HTTP response body does
                 (let [closed (promise)]
                   (proxy [java.io.InputStream] []
                     (read ([] (deref closed) (throw (java.io.IOException. "closed")))
                       ([_ _ _] (deref closed) (throw (java.io.IOException. "closed"))))
                     (close [] (deliver closed true)))))]
    (binding [chat/*stream-idle-ms* 300]
      (with-redefs-fn {#'dvergr.model.providers/ensure-initialized! (constantly nil)
                       #'dvergr.model.registry/resolve-alias identity
                       #'dvergr.model.registry/get-model! (constantly {:id "m" :provider :test-stream})
                       #'dvergr.model.registry/supports? (constantly true)
                       #'dvergr.model.registry/get-quirk (constantly nil)
                       #'dvergr.model.providers/get-provider! (fn [_] (provider))
                       #'dvergr.model.chat/make-request
                       (fn [& _]
                         {:status 200
                          :body (if (= 1 (swap! calls inc))
                                  (silent)
                                  (ByteArrayInputStream. (.getBytes events "UTF-8")))})}
        (fn []
          (let [started (System/currentTimeMillis)
                r (chat/chat [{:role "user" :content "hi"}] {:model "m" :provider :test-stream})]
            (testing "the second attempt's answer"
              (is (= "ok" (:content r))))
            (is (= 2 @calls) "the stalled stream was closed and the call made again")
            (is (< (- (System/currentTimeMillis) started) 10000) "within the idle limit, not forever")))))))

(defn- http-server [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.start server)
    server))

(defn- open-local-request [server]
  (let [origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
    (#'chat/make-request :test-stream origin {} {} (gateway/unauthenticated-credentials #{origin}) {})))

(deftest response-headers-have-a-deadline
  (let [accepted (promise)
        release (promise)
        server (http-server (fn [exchange]
                              (deliver accepted true)
                              @release
                              (.close exchange)))
        call (binding [chat/*request-open-timeout-ms* 300]
               (future (try (open-local-request server)
                            (catch Throwable e e))))]
    (try
      (is (= true (deref accepted 5000 ::timeout)) "the provider accepted the request")
      (is (instance? HttpTimeoutException (deref call 5000 ::timeout))
          "waiting for response headers ends even though the connection succeeded")
      (finally
        (deliver release true)
        (future-cancel call)
        (.stop server 0)))))

(deftest opening-deadline-does-not-limit-an-open-stream
  (let [release (promise)
        server (http-server
                (fn [exchange]
                  (try
                    (.sendResponseHeaders exchange 200 0)
                    (with-open [body (.getResponseBody exchange)]
                      (.write body (int \a))
                      (.flush body)
                      @release
                      (.write body (int \b)))
                    (finally (.close exchange)))))]
    (try
      (binding [chat/*request-open-timeout-ms* 300]
        (with-open [body (:body (open-local-request server))]
          (is (= (int \a) (.read body)))
          (let [next-byte (future (.read body))]
            (try
              (is (= ::waiting (deref next-byte 700 ::waiting))
                  "a valid open stream can outlive its opening deadline")
              (deliver release true)
              (is (= (int \b) (deref next-byte 5000 ::timeout)))
              (finally (future-cancel next-byte))))))
      (finally
        (deliver release true)
        (.stop server 0)))))

(deftest interrupted-opening-is-not-retried
  (doseq [error [(InterruptedException. "cancelled while opening")
                 (java.util.concurrent.CancellationException. "cancelled while opening")]]
    (let [calls (atom 0)]
      (with-redefs [gateway/request! (fn [_] (swap! calls inc) (throw error))]
        (is (identical? error
                        (try (chat/stream-chat (provider) {:id "m"} [] {})
                             (catch Exception e e))))
        (is (= 1 @calls) "an interrupted dispatch does not start a replacement request")))))

(deftest cancelled-opening-is-not-dispatched-again
  (let [calls (atom 0)
        cancelled? (atom false)]
    (with-redefs-fn {#'gateway/request! (fn [_]
                                          (swap! calls inc)
                                          (reset! cancelled? true)
                                          (throw (java.net.http.HttpTimeoutException. "no headers")))
                     #'dvergr.model.chat/calculate-backoff (constantly 0)}
      (fn []
        (is (thrown? java.util.concurrent.CancellationException
                     (chat/stream-chat (provider) {:id "m"} [] {:cancel? #(deref cancelled?)})))
        (is (= 1 @calls) "cancelling the opening wait prevents a retry dispatch")))))
