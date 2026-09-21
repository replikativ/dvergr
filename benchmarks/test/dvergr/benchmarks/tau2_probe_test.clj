(ns dvergr.benchmarks.tau2-probe-test
  "Decision-point probes rebuild a recorded episode's world and the
   candidate's history exactly; `tau2/shape` describes tool results."
  (:require [dvergr.test-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as ep]
            [dvergr.benchmarks.tau2.probe :as probe]
            [dvergr.benchmarks.tau2.pyjson :as pj]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/retail/db.json")))

(deftest data-shape-describes-structure
  (is (= {"variants" {"<2 keys, e.g. \"1\">" {"available" "boolean" "price" "number"}}
          "tags" ["<2 items>" "string"] "note" "null"}
         (ep/data-shape {"variants" {"1" {"available" true "price" 1.0}
                                     "2" {"available" false "price" 2.0}}
                         "tags" ["a" "b"] "note" nil})))
  (let [[[k v] :as entries] (seq (ep/data-shape (into {} (map (fn [i] [(str "k" i) (str i)])) (range 9))))]
    (is (= 1 (count entries)) "a large uniform scalar map is a lookup table")
    (is (re-matches #"<9 keys, e\.g\. \"k\d\">" k))
    (is (= "string" v))))

(deftest probe-prefix-replays-the-recorded-episode
  (if-not checkout?
    (support/skip! "probe-prefix-replays-the-recorded-episode: no ../tau2-bench checkout")
    (let [dom (t2/load-domain "retail")
          task (get-in dom [:tasks "0"])
          w0 ((:initial-world dom) task)
          args {"email" "yusuf.rossi7301@example.com"}
          {:keys [content]} ((:respond dom) w0 :assistant "find_user_id_by_email" args)
          addr {"user_id" "yusuf_rossi_9620" "address1" "1 Test Way" "address2" "" "city" "Austin"
                "state" "TX" "country" "USA" "zip" "78701"}
          w2 (:world ((:respond dom) w0 :assistant "modify_user_address" addr))
          log [{:seq 1 :kind :message :role :assistant :content t2/first-agent-message}
               {:seq 2 :kind :message :role :user :content "Find me."}
               {:seq 3 :kind :tool :requestor :assistant :tool "find_user_id_by_email"
                :arguments args :content content}
               {:seq 4 :kind :message :role :assistant :content "Found you."}
               {:seq 5 :kind :tool :requestor :assistant :tool "modify_user_address"
                :arguments addr :content "ok"}
               {:seq 6 :kind :message :role :user :content "Thanks."}]]
      (testing "the world before a cut replays every earlier effect"
        (is (= ((:world-hash dom) w2) ((:world-hash dom) (probe/world-at dom task log 6))))
        (is (= ((:world-hash dom) w0) ((:world-hash dom) (probe/world-at dom task log 3)))))
      (testing "history in the candidate's own action space, greeting excluded"
        (let [tools (probe/prefix-messages log 2 :tools)
              repl (probe/prefix-messages log 6 :repl)]
          (is (= [{:role :user :content "Find me."}] tools))
          (is (= [:user :assistant :tool-result :assistant :assistant :tool-result :user] (mapv :role repl)))
          (is (= (str "(tau2/find_user_id_by_email " (pr-str args) ")")
                 (get-in repl [1 :tool-uses 0 :tool-use/input :code])))
          (is (= (str "=> " (pr-str content)) (:content (nth repl 2))))))
      (is (= [[2 "Find me."] [6 "Thanks."]] (probe/customer-messages log))))))
