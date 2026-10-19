(ns dvergr.model.retired-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
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
    ;; Keep the global loaded flag consistent with the registry we restore.
    (registry/ensure-models-loaded!)
    (with-redefs [registry/registry (atom @registry/registry)
                  registry/aliases (atom @registry/aliases)
                  providers/ensure-initialized! (constantly nil)
                  providers/providers (atom {:openai (openai/create {:api-key "test"} {})
                                             :fireworks (openai/create-fireworks {:api-key "test"} {})})
                  vision/system-env (constantly nil)
                  registry/defaults (atom {})]
      (registry/load-models-from-edn (io/resource "models.edn"))
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
        (is (= (fireworks successor)
               (:model (chat/chat [{:role "user" :content "hi"}] {:model (fireworks old)}))))
        (is (= [(fireworks successor)] (mapv :model @requests)))))))

(deftest fireworks-vision-accepts-image-parts
  (doseq [[opts expected] [[{} (fireworks "kimi-k3")]
                           [{:model "glm-flash"} (fireworks "glm-5p3-flash")]
                           [{:model (fireworks "kimi-k2p6")} (fireworks "kimi-k3")]
                           [{:model "gpt-5.4-mini"} "gpt-5.4-mini"]]]
    (let [request (atom nil)]
      (is (registry/supports? expected :vision))
      (with-redefs [gateway/request! (fn [r]
                                       (reset! request (json/read-value (:body r) json/keyword-keys-object-mapper))
                                       {:status 200 :body (stream "data: {\"choices\":[{\"delta\":{\"content\":\"a cat\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")})]
        (is (= "a cat" (vision/describe (byte-array [1 2 3]) "image/png" opts)))
        (is (= expected (:model @request)))
        (is (= "data:image/png;base64,AQID"
               (get-in @request [:messages 0 :content 0 :image_url :url])))))))

(deftest unavailable-model-errors-name-model-and-config
  (let [calls (atom 0)]
    (with-redefs [gateway/request! (fn [_]
                                     (swap! calls inc)
                                     {:status 404 :body (stream "{\"error\":{\"message\":\"Model not found\"}}")})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"kimi-k3 from DVERGR_VISION_MODEL.*404.*Model not found"
                            (vision/describe (byte-array [1]) "image/png")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"kimi-k3 from DVERGR_VISION_MODEL"
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
                          (vision/describe (byte-array [1]) "image/png" {:model (fireworks "minimax-m3")})))))

(deftest resolution-warns-once-with-both-ids
  (let [old (fireworks "kimi-k2p6")
        successor (fireworks "kimi-k3")
        {:keys [value error signals]} (tel/with-signals true (registry/resolve-alias old))]
    (is (nil? error))
    (is (= successor value))
    (is (= 1 (count signals)))
    (is (= :warn (:level (first signals))))
    (is (= {:model old :successor successor} (:data (first signals))))))

(deftest vision-requires-usable-routing
  (with-redefs [gateway/request! (fn [_] (throw (AssertionError. "must fail before HTTP")))]
    (with-redefs [registry/defaults (atom {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":vision-model.*DVERGR_VISION_MODEL"
                            (vision/describe (byte-array [1]) "image/png")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":vision-model"
                            (vision/extract (byte-array [1]) "image/png" {:schema "total"}))))
    ;; Use the real constructor and lookup: this is the documented Fireworks env.
    (let [env {"OPENAI_API_KEY" "test" "OPENAI_BASE_URL" "https://api.fireworks.ai/inference/v1"}]
      (with-redefs [providers/providers (atom {:openai (openai/create {} env)})
                    vision/system-env {"DVERGR_VISION_MODEL" "gpt-5.4-mini"}]
        (doseq [invoke [#(vision/describe (byte-array [1]) "image/png")
                        #(vision/extract (byte-array [1]) "image/png" {:schema "total"})]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #":vision-model.*OPENAI_BASE_URL"
                                (invoke))))))))

(deftest cheap-call-attributes-successor
  (with-redefs [gateway/request! (fn [_] {:status 200 :body (stream "data: [DONE]\n\n")})]
    (is (= (fireworks "kimi-k3")
           (:model (llm/cheap-llm-call "hi" "" {:model "kimi-k2p5"}))))))

(deftest bundled-vision-default-is-served-and-image-capable
  (let [mid (get-in (edn/read-string (slurp (io/resource "models.edn")))
                    [:defaults :vision-model])]
    (is (= (fireworks "kimi-k3") mid))
    (is (not (:retired (registry/get-model! mid))))
    (is (registry/supports? mid :vision))))

(deftest vision-configuration-precedence
  (with-redefs [vision/system-env {"DVERGR_VISION_MODEL" "gpt-5.4-mini"}
                registry/defaults (atom {:vision-model (fireworks "kimi-k3")})
                gateway/request! (fn [_] {:status 200 :body (stream "data: [DONE]\n\n")})]
    (is (= "gpt-5.4-mini" (#'vision/model-id nil)))
    (is (= "gpt-5.5" (#'vision/model-id "gpt-5.5"))))
  (with-redefs [registry/defaults (atom {:vision-model "glm-flash"})]
    (is (= (fireworks "glm-5p3-flash") (#'vision/model-id nil))))
  (with-redefs [providers/providers (atom {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":vision-model.*provider :openai is not registered"
                          (#'vision/model-id "gpt-5.4-mini")))))

(deftest describe-preserves-provider-error-details
  (doseq [[status message] [[400 "Image input rejected"]
                            [404 "Model not found"]
                            [503 "No capacity for this model"]]]
    (with-redefs [gateway/request! (fn [_]
                                     {:status status
                                      :body (stream (json/write-value-as-string {:error {:message message}}))})
                  chat/retry-config (assoc @#'chat/retry-config :base-delay-ms 0 :max-delay-ms 0)]
      (let [error (try (vision/describe (byte-array [1]) "image/png")
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= {:status status :message message :model (fireworks "kimi-k3")}
               (select-keys (ex-data error) [:status :message :model])))
        (is (re-find (re-pattern (str status)) (ex-message error)))
        (is (.contains (ex-message error) message))))))

(deftest default-vision-routing-must-serve-fireworks
  (with-redefs [providers/providers (atom {:fireworks (openai/create {:api-key "test"} {})})
                gateway/request! (fn [_] (throw (AssertionError. "must fail before HTTP")))]
    (doseq [invoke [#(vision/describe (byte-array [1]) "image/png")
                    #(vision/extract (byte-array [1]) "image/png" {:schema "total"})]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":vision-model.*DVERGR_VISION_MODEL.*kimi-k3.*OPENAI_BASE_URL"
                            (invoke))))))

(deftest default-vision-extract-sends-images
  (let [request (atom nil)]
    (with-redefs [gateway/request! (fn [r]
                                     (reset! request (json/read-value (:body r) json/keyword-keys-object-mapper))
                                     {:status 200 :body (stream "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"total\\\":12}\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")})]
      (is (= {:data {:total 12}}
             (vision/extract (byte-array [1]) "image/png" {:schema "total"})))
      (is (= (fireworks "kimi-k3") (:model @request)))
      (is (= "image_url" (get-in @request [:messages 0 :content 0 :type]))))))

(defn- assert-no-secrets [error secrets]
  (is (instance? clojure.lang.ExceptionInfo error))
  (doseq [cause (take-while some? (iterate ex-cause error))
          exposed [(ex-message cause) (pr-str (ex-data cause))]
          secret secrets]
    (is (not (str/includes? exposed secret))))
  (is (nil? (ex-cause error)))
  (is (<= (count (ex-message error)) 1024)))

(deftest vision-errors-expose-only-sanitized-diagnostics
  (let [secrets ["sk-SENTINEL_KEY" "SENTINEL_API" "SENTINEL_BEARER"
                 "SENTINEL_AUTH" "SENTINEL_SPACED_KEY" "SENTINEL_QUERY" "SENTINEL_USER"
                 "SENTINEL_PASSWORD" "SENTINEL_HEADER" "SENTINEL_REQUEST"
                 "gsk_SENTINEL_GROQ" "SENTINEL_REFRESH" "SENTINEL_SECRET"]
        message (str "Image rejected sk-SENTINEL_KEY api_key=SENTINEL_API, "
                     "gsk_SENTINEL_GROQ refresh_token=SENTINEL_REFRESH secret=SENTINEL_SECRET, "
                     "API key: SENTINEL_SPACED_KEY, Bearer SENTINEL_BEARER; Authorization: Basic SENTINEL_AUTH\n"
                     "https://SENTINEL_USER:SENTINEL_PASSWORD@example.com/v1?access_token=SENTINEL_QUERY")
        provider-error (ex-info message {:status 400 :message message
                                         :headers {"x-secret" "SENTINEL_HEADER"}
                                         :request {:body "SENTINEL_REQUEST"}}
                                (Exception. "SENTINEL_REQUEST"))]
    (doseq [error [provider-error
                   (ex-info message {:type :retry-exhausted :provider :fireworks
                                     :config-key "DVERGR_VISION_MODEL"
                                     :headers {"secret" "SENTINEL_HEADER"}
                                     :request "SENTINEL_REQUEST"}
                            provider-error)
                   (ex-info message {:message message :model {:request "SENTINEL_REQUEST"}})
                   (Exception. message)]]
      (with-redefs [chat/chat (fn [& _] (throw error))]
        (let [caught (try (vision/describe (byte-array [1]) "image/png")
                          (catch Exception e e))]
          (assert-no-secrets caught secrets)
          (is (every? #{:type :status :message :model :config-key :provider}
                      (keys (ex-data caught))))
          (when (:status (ex-data caught))
            (is (= 400 (:status (ex-data caught))))
            (is (str/includes? (:message (ex-data caught)) "Image rejected"))))))
    (with-redefs [chat/chat (fn [& _] (throw (ex-info (apply str (repeat 3000 "x"))
                                                      {:message (apply str (repeat 3000 "y"))})))]
      (let [caught (try (vision/describe (byte-array [1]) "image/png")
                        (catch Exception e e))]
        (is (= 1024 (count (ex-message caught))))
        (is (= 1024 (count (:message (ex-data caught)))))))))

(deftest vision-routing-diagnostic-omits-url-credentials
  (let [url (str "https://SENTINEL_USER:SENTINEL_PASSWORD@api.fireworks.ai:8443/inference/v1"
                 "?api_key=SENTINEL_QUERY#SENTINEL_FRAGMENT")]
    (with-redefs [providers/providers (atom {:openai (openai/create {:api-key "test" :base-url url} {})})
                  gateway/request! (fn [_] (throw (AssertionError. "must fail before HTTP")))]
      (doseq [invoke [#(vision/describe (byte-array [1]) "image/png" {:model "gpt-5.4-mini"})
                      #(vision/extract (byte-array [1]) "image/png" {:model "gpt-5.4-mini" :schema "total"})]]
        (let [caught (try (invoke) (catch Exception e e))]
          (assert-no-secrets caught ["SENTINEL_USER" "SENTINEL_PASSWORD" "SENTINEL_QUERY" "SENTINEL_FRAGMENT"])
          (is (str/includes? (ex-message caught) "https://api.fireworks.ai:8443/inference/v1"))
          (is (not (str/includes? (ex-message caught) "?"))))))))

(deftest vision-routing-errors-are-bounded-and-hide-invalid-urls
  (doseq [url ["https://user:SENTINEL_PASSWORD@api.fireworks.ai/bad path?token=SENTINEL_QUERY"
               (str "https://api.fireworks.ai/" (apply str (repeat 2000 "x")))]]
    (with-redefs [providers/providers (atom {:openai (assoc-in (openai/create {:api-key "test"} {}) [:config :base-url] url)})
                  gateway/request! (fn [_] (throw (AssertionError. "must fail before HTTP")))]
      (let [caught (try (vision/describe (byte-array [1]) "image/png" {:model "gpt-5.4-mini"})
                        (catch Exception e e))]
        (assert-no-secrets caught ["SENTINEL_PASSWORD" "SENTINEL_QUERY"])))))
