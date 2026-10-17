(ns dvergr.model.zero-priced-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.spend :as spend]
            [dvergr.model.api.codex-subscription :as codex]
            [dvergr.model.chat :as chat]
            [dvergr.model.gateway :as gateway]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.model.reservation-test :as fixture]
            [dvergr.resource :as resource]
            [jsonista.core :as json])
  (:import [java.io ByteArrayInputStream IOException]
           [java.util.concurrent CancellationException]))

(defn funded [dispatches f]
  (@#'fixture/with-funded-run dispatches 10000M f))
(def request @#'fixture/request)

(deftest zero-is-a-real-bound-not-an-invented-price-or-output-cap
  (is (= 0M (resource/dispatch-bound (registry/get-model! "codex-subscription-sol") {} 100 nil)))
  (is (= 0M (resource/dispatch-bound {:pricing {:input 0 :output 0 :cache-read 0 :cache-write 0}}
                                     {:input [{:type "input_file" :file_id "f"}]} 100 nil)))
  (doseq [model [{:pricing {}} {:pricing {:input 0}} {:pricing {:output 0}}
                 {:long-context {:pricing {:input 0 :output 0}}}
                 {:pricing {:input 0} :long-context {:pricing {:output 0}}}
                 {:pricing {:input 0 :output 1}}
                 {:pricing {:input 0 :output 0 :cache-read 1}}
                 {:pricing {:input 0 :output 0 :cache-write 1}}
                 {:pricing {:input 0 :output 0 :cache-write-1h 1}}
                 {:pricing {:input 0 :output 0} :long-context {:pricing {:output 1}}}
                 {:pricing {:input -1 :output 0}}
                 {:pricing {:input ##NaN :output 0}}
                 {:pricing {:input 0 :output ##Inf}}
                 {:pricing {:input 0 :output 0 :cache-read nil}}
                 {:pricing {:input 0 :output 0} :long-context {:pricing {:input -1}}}]]
    (is (nil? (resource/dispatch-bound model {} 100 nil)) (pr-str model)))
  (is (pos? (resource/dispatch-bound {:pricing {:input 0 :output 0 :cache-read 1}} {} 100 10))))

(deftest zero-dollar-dispatch-still-consumes-every-model-admission
  (funded
   2M (fn [room id]
        (let [calls (atom 0)]
          (binding [gateway/*request-fn* (fn [_] (swap! calls inc) {:status 200})
                    resource/*dispatches* (atom []) resource/*dispatch-bound* 0M]
            (dotimes [_ 2] (is (= 200 (:status (gateway/request! request)))))
            (is (thrown? clojure.lang.ExceptionInfo (gateway/request! request)))
            (is (= 2 @calls))
            (is (empty? @resource/*dispatches*) "No fictional positive-dollar reservation")
            (resource/settle-dispatches! 0M)
            (is (= {resource/microdollars 10000M} (resource/run-balance room id))))))))

(deftest zero-dollar-auth-retries-are-not-free-dispatches
  (doseq [admissions [1M 2M]]
    (funded
     admissions
     (fn [room id]
       (let [calls (atom 0)
             credentials (reify gateway/Credentials
                           (credential-kind [_] :fixture)
                           (allowed-origins [_] #{"https://model.test"})
                           (resolve-auth! [_ _] {:headers {} :version :test})
                           (recover-auth! [_ _ _] true))]
         (binding [gateway/*request-fn* (fn [_] {:status (if (= 1 (swap! calls inc)) 401 200)})
                   resource/*dispatches* (atom []) resource/*dispatch-bound* 0M]
           (if (= 1M admissions)
             (is (thrown? clojure.lang.ExceptionInfo (gateway/request! (assoc request :credentials credentials))))
             (is (= 200 (:status (gateway/request! (assoc request :credentials credentials))))))
           (is (= (int admissions) @calls))
           (is (= {resource/microdollars 10000M} (resource/run-balance room id)))))))))

(deftest cancellation-and-ambiguous-zero-dollar-dispatch-keep-existing-fences
  (funded
   1M (fn [room id]
        (let [calls (atom 0)]
          (binding [gateway/*request-fn* (fn [_] (swap! calls inc) (throw (IOException. "ambiguous")))
                    resource/*dispatches* (atom []) resource/*dispatch-bound* 0M]
            (binding [resource/*model-scope* (assoc resource/*model-scope* :cancel? (constantly true))]
              (is (thrown? CancellationException (gateway/request! request))))
            (is (zero? @calls))
            (is (thrown? IOException (gateway/request! request)))
            (is (thrown? clojure.lang.ExceptionInfo (gateway/request! request)))
            (is (= 1 @calls))
            (resource/settle-dispatches! nil)
            (is (= {resource/microdollars 10000M} (resource/run-balance room id))))))))

(deftest zero-does-not-bypass-owning-model-call-or-invalid-bound-checks
  (funded
   1M (fn [room id]
        (let [calls (atom 0)]
          (binding [gateway/*request-fn* (fn [_] (swap! calls inc) {:status 200})
                    resource/*dispatch-bound* 0M]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"settles it" (gateway/request! request))))
          (doseq [bad [nil -1M ##NaN ##Inf]]
            (binding [gateway/*request-fn* (fn [_] (swap! calls inc) {:status 200})
                      resource/*dispatches* (atom []) resource/*dispatch-bound* bad]
              (is (thrown? clojure.lang.ExceptionInfo (gateway/request! request)))))
          (is (zero? @calls))
          (is (= {resource/model-dispatches 1M resource/microdollars 10000M} (resource/run-balance room id)))))))

(deftest actual-native-subscription-stream-keeps-notional-and-incremental-cost-distinct
  (funded
   1M (fn [room id]
        (let [model "codex-subscription-sol"
              provider (codex/create {:credentials (gateway/static-credentials :fixture {} #{"https://chatgpt.com"})})
              events [{:type "response.output_text.delta" :delta "done"}
                      {:type "response.completed" :response {:id "fixture" :model "gpt-5.6-sol"
                                                             :usage {:input_tokens 100 :output_tokens 50}}}]
              body (apply str (map #(str "data: " (json/write-value-as-string %) "\n\n") events))
              calls (atom 0)]
          (binding [gateway/*request-fn* (fn [_] (swap! calls inc)
                                           {:status 200 :body (ByteArrayInputStream. (.getBytes body "UTF-8"))})]
            (with-redefs [providers/ensure-initialized! (constantly nil) providers/get-provider! (constantly provider)]
              (let [response (chat/chat [{:role "user" :content "hello"}] {:model model})
                    usage (spend/of-usage model (:usage response))]
                (is (= "done" (:content response)))
                (is (= 0 (:microdollars usage)))
                (is (pos? (:notional-microdollars usage)))
                (is (= 1 @calls))
                (is (= {resource/microdollars 10000M} (resource/run-balance room id))))))))))
