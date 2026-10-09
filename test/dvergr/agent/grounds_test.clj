(ns dvergr.agent.grounds-test
  "An Attempt's grounds from its receipts, and the overlap that makes two
   agreeing Attempts one source counted twice."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.grounds :as grounds]))

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
                        :resource {:method :get :url "https://api.search/s" :query {"q" q "count" "10"}}
                        :decision :allowed :digest q})
        r (grounds/independence (grounds/grounds [(search "agent teams")])
                                (grounds/grounds [(search "agent teams") (search "memory")]))]
    (is (= #{"https://api.search/s?count=10&q=agent teams"} (:shared r)))
    (is (= 0.5 (:overlap r)))))

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
