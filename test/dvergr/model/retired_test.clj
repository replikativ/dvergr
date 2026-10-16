(ns dvergr.model.retired-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dvergr.media.vision :as vision]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.chat :as chat]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.tools.llm-call :as llm]
            [jsonista.core :as json]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayInputStream]))

(def successors
  {"kimi-k2p6" "kimi-k3" "kimi-k2p5" "kimi-k3"
   "glm-5p2" "glm-5p3" "minimax-m2p7" "minimax-m3"})

(defn- fireworks [id] (str "accounts/fireworks/models/" id))
(defn- stream [s] (ByteArrayInputStream. (.getBytes s "UTF-8")))

(use-fixtures :each
  (fn [f]
    (registry/ensure-models-loaded!)
    (with-redefs [providers/ensure-initialized! (constantly nil)
                  providers/get-provider! (fn [_] (openai/create {:api-key "test"} {}))]
      (f))))

(deftest retired-ids-and-aliases-resolve
  (doseq [[old successor] successors]
    (let [id (fireworks old)]
      (is (true? (:retired (registry/get-model! id))))
      (is (= (fireworks successor) (:successor (registry/get-model! id))))
      (is (= (fireworks successor) (registry/resolve-alias id)))))
  (is (= (fireworks "kimi-k3") (registry/resolve-alias "kimi")))
  (is (= (fireworks "glm-5p3") (registry/resolve-alias "glm"))))

(deftest successor-is-sent-on-the-wire
  (doseq [[old successor] successors]
    (let [requests (atom [])]
      (with-redefs [gateway/request! (fn [request]
                                       (swap! requests conj (json/read-value (:body request) json/keyword-keys-object-mapper))
                                       {:status 200 :body (stream "data: [DONE]\n\n")})]
        (chat/chat [{:role "user" :content "hi"}] {:model (fireworks old)})
        (is (= [(fireworks successor)] (mapv :model @requests)))))))

(deftest vision-default-accepts-image-parts
  (let [request (atom nil)]
    (is (= "gpt-5.4-mini" (registry/get-default :vision-model)))
    (is (registry/supports? (registry/get-default :vision-model) :vision))
    (is (not-any? #(and (= :fireworks (:provider %))
                        (not (:retired %))
                        (contains? (:capabilities %) :vision))
                  (registry/list-models)))
    (with-redefs [gateway/request! (fn [r]
                                     (reset! request (json/read-value (:body r) json/keyword-keys-object-mapper))
                                     {:status 200 :body (stream "data: {\"choices\":[{\"delta\":{\"content\":\"a cat\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")})]
      (is (= "a cat" (vision/describe (byte-array [1 2 3]) "image/png")))
      (is (= "gpt-5.4-mini" (:model @request)))
      (is (= "data:image/png;base64,AQID"
             (get-in @request [:messages 0 :content 0 :image_url :url]))))))

(deftest unavailable-model-errors-name-model-and-config
  (let [calls (atom 0)]
    (with-redefs [gateway/request! (fn [_]
                                     (swap! calls inc)
                                     {:status 404 :body (stream "{\"error\":{\"message\":\"Model not found\"}}")})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"gpt-5.4-mini from DVERGR_VISION_MODEL.*404.*Model not found"
                            (vision/describe (byte-array [1]) "image/png")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gpt-5.4-mini from DVERGR_VISION_MODEL"
                            (vision/extract (byte-array [1]) "image/png" {:schema "total"})))
      (is (re-find #"minimax-m3 from LLM_CALL_MODEL.*404.*Model not found"
                   (:error (llm/cheap-llm-call "summarize" "text" {}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"minimax-m3 from :model"
                            (chat/chat [] {:model (fireworks "minimax-m2p7")})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gpt-5.4-mini from :model"
                            (vision/describe (byte-array [1]) "image/png" {:model "gpt-5.4-mini"})))
      (is (= 5 @calls) "404 is not retried"))))

(deftest failed-describe-is-a-failure
  (with-redefs [gateway/request! (fn [_] {:status 200 :body (stream "data: [DONE]\n\n")})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Vision describe returned no text"
                          (vision/describe (byte-array [1]) "image/png"))))
  (with-redefs [gateway/request! (fn [_] (throw (AssertionError. "text-only model must not reach HTTP")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support :vision"
                          (vision/describe (byte-array [1]) "image/png" {:model (fireworks "kimi-k2p6")})))))

(deftest resolution-warns-once-with-both-ids
  (let [old (fireworks "kimi-k2p6")
        successor (fireworks "kimi-k3")
        {:keys [value error signals]} (tel/with-signals true (registry/resolve-alias old))]
    (is (nil? error))
    (is (= successor value))
    (is (= 1 (count signals)))
    (is (= :warn (:level (first signals))))
    (is (= {:model old :successor successor} (:data (first signals))))))
