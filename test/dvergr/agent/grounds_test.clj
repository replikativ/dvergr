(ns dvergr.agent.grounds-test
  "An Attempt's grounds from its receipts, and the overlap that makes two
   agreeing Attempts one source counted twice."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.grounds :as grounds]
            [dvergr.artifact :as artifact]
            [malli.core :as m]
            [dvergr.effects :as effects]
            [dvergr.sandbox.ns.io :as io]
            [sci.core :as sci]))

(defn- fetch [url digest]
  {:effect :http/request :resource {:method :get :url url}
   :decision :allowed :digest digest})

(defn- read-file [path digest]
  {:effect :fs/read :resource {:path path} :decision :allowed :digest digest})

(deftest only-completed-reads-are-grounds
  (let [g (grounds/grounds
           [(fetch "https://a.example/x" "d1")
            (fetch "https://a.example/x" "d1")
            (read-file "notes.md" "f1")
            {:effect :fs/write :resource {:path "out.md"} :decision :allowed :digest "w"}
            {:effect :model/call :resource {:model "m"} :decision :allowed :digest "m"}
            (assoc (fetch "https://denied.example" nil) :decision :denied)
            (assoc (fetch "https://failed.example" nil) :error "java.io.IOException")])]
    (is (= {"https://a.example/x" #{"d1"}} (:external g)))
    (is (= {"file:notes.md" #{"f1"}} (:local g)))
    (is (= {:external 1 :local 1} (grounds/summary g)))))

(deftest independence-is-overlap-of-external-grounds
  (let [ga (grounds/grounds [(fetch "https://p/1" "a") (fetch "https://p/2" "b")
                             (read-file "task.md" "t")])
        gb (grounds/grounds [(fetch "https://p/2" "b") (fetch "https://p/3" "c")
                             (read-file "task.md" "t")])
        r (grounds/independence ga gb)]
    (testing "shared local reads are the common base, not added evidence"
      (is (= #{"https://p/2"} (:shared r))))
    (is (= 1 (:only-a r)))
    (is (= 1 (:only-b r)))
    (is (= (/ 1.0 3) (:overlap r)))
    (is (empty? (:changed r)))))

(deftest a-source-that-changed-between-reads-is-flagged
  (let [r (grounds/independence (grounds/grounds [(fetch "https://live/page" "v1")])
                                (grounds/grounds [(fetch "https://live/page" "v2")]))]
    (is (= 1.0 (:overlap r)))
    (is (= #{"https://live/page"} (:changed r)))))

(deftest a-search-is-its-endpoint-and-query
  (let [search (fn [q] {:effect :http/request
                        :resource {:method :get :url "https://api.search/s" :query (str "q=" q "&count=10")}
                        :decision :allowed :digest q})
        r (grounds/independence (grounds/grounds [(search "agent+teams")])
                                (grounds/grounds [(search "agent+teams") (search "memory")]))]
    (is (= #{"https://api.search/s?count=10&q=agent%20teams"} (:shared r)))
    (is (= 0.5 (:overlap r)))))

(deftest a-query-joins-the-one-in-the-url
  (is (= {"https://api.search/s?lang=en&q=x" #{"d"}}
         (:external (grounds/grounds [{:effect :http/request
                                       :resource {:method :get :url "https://api.search/s?lang=en#top" :query "q=x"}
                                       :decision :allowed :digest "d"}])))))

(deftest a-posted-search-is-its-endpoint-and-body
  (let [post (fn [body-digest] {:effect :http/request
                                :resource {:method :post :url "https://api.search/graphql" :body-digest body-digest}
                                :decision :allowed :digest body-digest})
        r (grounds/independence (grounds/grounds [(post "b1")])
                                (grounds/grounds [(post "b1") (post "b2")]))]
    (is (= #{"POST https://api.search/graphql body=b1"} (:shared r)))
    (is (= 0.5 (:overlap r)))))

(deftest writes-are-not-grounds-and-the-method-names-the-source
  (let [req (fn [method] {:effect :http/request :resource {:method method :url "https://api/doc/1"}
                          :decision :allowed :digest "d"})
        g (grounds/grounds (map req [:get :head :delete :put :patch]))]
    (is (= #{"https://api/doc/1" "HEAD https://api/doc/1"} (set (keys (:external g)))))))

(deftest no-external-reads-is-no-evidence-not-independence
  (is (nil? (:overlap (grounds/independence (grounds/grounds [(read-file "a" "x")])
                                            (grounds/grounds []))))))

(deftest shared-sources-lists-overlapping-pairs-highest-first
  (let [gs (mapv grounds/grounds
                 [[(fetch "https://p/1" "a") (fetch "https://p/2" "b")]
                  [(fetch "https://p/1" "a") (fetch "https://p/2" "b")]
                  [(fetch "https://p/2" "b") (fetch "https://p/9" "z")]
                  [(fetch "https://q/1" "q")]])]
    (is (= [{:attempts [0 1] :overlap 1.0 :shared ["https://p/1" "https://p/2"]}
            {:attempts [0 2] :overlap 0.33 :shared ["https://p/2"]}
            {:attempts [1 2] :overlap 0.33 :shared ["https://p/2"]}]
           (grounds/shared-sources gs)))))

(defn- sandbox-receipts
  "The receipts of evaluating `code` (http calls) in a sandbox whose requests
   reach an offline transport."
  [code]
  (let [sink (effects/make-sink)
        ctx (sci/init {})]
    (io/add-http-ns! ctx :effects (constantly {:handlers [(effects/receipts sink nil)]})
                     :fixture-transport (fn [_] {:status 200 :headers {} :body "ok"}))
    (sci/eval-string* ctx code)
    @sink))

(defn- sources [receipts]
  (mapv #(second (grounds/source %)) receipts))

(deftest one-request-written-two-ways-is-one-source
  (let [[a b] (sources (sandbox-receipts
                        (str "(babashka.http-client/get \"https://api/s?q=a%20b&lang=en#frag\")"
                             "(babashka.http-client/get \"https://api/s\" {:query-params {:lang \"en\" :q \"a b\"}})")))]
    (is (= a b))))

(deftest reserved-characters-and-repeated-parameters-stay-distinct
  (let [rs (sandbox-receipts
            (str "(babashka.http-client/get \"https://api/s\" {:query-params {:q \"x&r=y\"}})"
                 "(babashka.http-client/get \"https://api/s\" {:query-params {:q \"x\" :r \"y\"}})"
                 "(babashka.http-client/get \"https://api/s\" {:query-params {:t [\"a\" \"b\"]}})"
                 "(babashka.http-client/get \"https://api/s\" {:query-params {:t \"[\\\"a\\\" \\\"b\\\"]\"}})"))
        [amp two vec lit] (sources rs)]
    (is (not= amp two) "an encoded & is not a second parameter")
    (is (= "https://api/s?t=a&t=b" vec) "a vector is one parameter per element, as sent")
    (is (not= vec lit))
    (testing "and replay tells them apart"
      (is (= 4 (count (distinct (map effects/effect-key rs))))))
    (testing "receipts have the vocabulary's shape"
      (is (every? #(m/validate (get-in effects/vocabulary [:http/request :resource]) (:resource %)) rs)))))

(deftest nested-parameters-and-the-order-of-repeated-ones-are-kept
  (let [[nested literal ab ba] (sources (sandbox-receipts
                                         (str "(babashka.http-client/get \"https://api/s\" {:query-params {:filter {:q \"x\"}}})"
                                              "(babashka.http-client/get \"https://api/s\" {:query-params {:filter \"{:q \\\"x\\\"}\"}})"
                                              "(babashka.http-client/get \"https://api/s?t=a&t=b\")"
                                              "(babashka.http-client/get \"https://api/s?t=b&t=a\")")))]
    (is (= "https://api/s?filter%5Bq%5D=x" nested) "as hato sends it")
    (is (not= (first (sources (sandbox-receipts "(babashka.http-client/get \"https://api/s?q=%E9\")")))
              (first (sources (sandbox-receipts "(babashka.http-client/get \"https://api/s?q=%FF\")"))))
        "bytes that are not UTF-8 stay distinct")
    (is (not= nested literal))
    (is (not= ab ba))))

(deftest a-byte-body-is-digested-as-sent
  (let [[bytes-e9 string-e] (map (comp :body-digest :resource)
                                 (sandbox-receipts
                                  (str "(babashka.http-client/post \"https://api/g\" {:body (byte-array [(unchecked-byte 0xE9)])})"
                                       "(babashka.http-client/post \"https://api/g\" {:body \"\u00e9\"})")))]
    (is (and bytes-e9 string-e))
    (is (not= bytes-e9 string-e) "0xE9 is not the UTF-8 bytes of é")))

(deftest the-digest-is-of-the-body-the-transport-sends
  (let [[same-body-a same-body-b form-only] (map :resource
                                                 (sandbox-receipts
                                                  (str "(babashka.http-client/post \"https://api/g\" {:body \"x\" :json {:q 1}})"
                                                       "(babashka.http-client/post \"https://api/g\" {:body \"x\" :json {:q 2}})"
                                                       "(babashka.http-client/post \"https://api/g\" {:form-params {:q 1}})")))]
    (is (not= (:body-digest same-body-a) (:body-digest same-body-b)) ":json is what is sent")
    (is (nil? (:body-digest form-only)) "the transport sends no form body")))

(deftest an-attempts-receipts-are-read-back-from-its-room
  ;; the path evaluation stores the log on and workflow results read it from
  (let [room {:store {:artifacts (artifact/memory-store)}}
        receipts [(fetch "https://p/1" "a") (read-file "task.md" "t")]
        {:keys [log]} (#'evaluation/effect-log room receipts)
        attempt {:attempt/receipt {:attempt/metrics {:effects {:log log}}}}]
    (is (some? log))
    (is (= receipts (grounds/attempt-receipts room attempt)))
    (is (= {:external 1 :local 1}
           (grounds/summary (grounds/grounds (grounds/attempt-receipts room attempt)))))))

(deftest sandbox-requests-receipt-what-they-asked
  (let [sink (effects/make-sink)
        ctx (sci/init {})
        offline (fn [_] {:status 200 :headers {} :body "ok"})]
    (io/add-http-ns! ctx :effects (constantly {:handlers [(effects/receipts sink nil)]})
                     :fixture-transport offline)
    (sci/eval-string* ctx (str "(babashka.http-client/post \"https://api.search/graphql\" {:body \"{\\\"q\\\":\\\"a\\\"}\"})"
                               "(babashka.http-client/post \"https://api.search/graphql\" {:json {:q \"b\"}})"
                               "(babashka.http-client/get \"https://api.search/s?lang=en\" {:query-params {:q \"x\"}})"))
    (let [[a b c] (mapv :resource @sink)
          sources (mapv #(grounds/source (assoc % :decision :allowed)) @sink)]
      (is (string? (:body-digest a)))
      (is (not= (:body-digest a) (:body-digest b)) "two bodies, two sources")
      (is (nil? (:body-digest c)) "a GET without a body has none")
      (is (= [:external "https://api.search/s?lang=en&q=x"] (nth sources 2))))))
