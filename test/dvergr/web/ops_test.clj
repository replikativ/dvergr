(ns dvergr.web.ops-test
  "Daemon-free checks on the web action-route table — in particular that the
   room-action routes accept slugs containing a slash (fork rooms slug as
   `<parent>/fork-<hash>`, e.g. `tg-79524334/fork-c248da6f`), which a single
   path-segment regex would miss."
  (:require [clojure.test :refer [deftest is testing]]
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
