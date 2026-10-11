(ns dvergr.web.ops-test
  "Daemon-free checks on the web action-route table — in particular that the
   room-action routes accept slugs containing a slash (fork rooms slug as
   `<parent>/fork-<hash>`, e.g. `tg-79524334/fork-c248da6f`), which a single
   path-segment regex would miss."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.ops :as aops]
            [dvergr.web.ops :as web-ops]))

(deftest room-action-routes-accept-slashed-slugs
  (testing "each room-action route matches a fork slug that contains a slash"
    (let [routes @#'web-ops/routes
          slug   "tg-79524334/fork-c248da6f"]
      (doseq [op [:room/merge :room/discard :room/fork :room/delete]]
        (let [{:keys [path args]} (first (filter #(= op (:op %)) routes))
              uri (str "/api/rooms/" slug "/" (name op))
              m   (re-matches path uri)]
          (is (vector? m) (str op " route matches " uri))
          (is (= {:room slug} (args (vec (rest m)) nil))
              (str op " extracts the full slug as :room")))))))

(defn- post-form [uri body]
  (web-ops/dispatch {:request-method :post :uri uri
                     :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))}
                    {:execution-ctx nil}))

(deftest a-failed-form-op-answers-with-its-status
  (testing "the agent forms are held to the ops' rules: a bad value is a 400 and writes nothing"
    (let [writes (atom 0)]
      (with-redefs [aops/update-agent! (fn [& _] (swap! writes inc) {:id :a})
                    aops/get-agent (fn [_] nil)]
        (let [resp (post-form "/agents/a/config" "name=New&budget=lots")]
          (is (= 400 (:status resp)))
          (is (re-find #"budget" (:body resp))))
        (is (= 400 (:status (post-form "/agents/new" "id=+++"))) "a blank id")
        (is (zero? @writes)))))
  (testing "an unknown agent is a 404"
    (with-redefs [aops/update-agent! (fn [& _] nil)]
      (is (= 404 (:status (post-form "/agents/ghost/config" "name=X")))))))
