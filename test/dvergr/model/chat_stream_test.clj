(ns dvergr.model.chat-stream-test
  "A response stream that goes quiet is closed and asked again, not waited on
   until something above gives up."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.chat :as chat]
            [dvergr.model.provider :as p])
  (:import [java.io ByteArrayInputStream]))

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
