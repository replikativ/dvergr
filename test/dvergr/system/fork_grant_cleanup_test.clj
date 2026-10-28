(ns dvergr.system.fork-grant-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.datahike :as substrate]
            [dvergr.system.rooms :as rooms]))

(deftest strict-grant-removal-requires-an-acknowledged-absent-store
  (let [deleted (atom []) cfg {:store {:backend :memory :id :fixture}}]
    (with-redefs-fn
      {#'rooms/data-cfg (constantly cfg)
       #'substrate/delete-database! (fn [config] (swap! deleted conj config))
       #'d/database-exists? (constantly false)}
      #(is (nil? (rooms/drop-fork-grant! {:scope :fixture}))))
    (is (= [cfg] @deleted))
    (with-redefs-fn
      {#'rooms/data-cfg (constantly cfg)
       #'substrate/delete-database! (fn [_])
       #'d/database-exists? (constantly true)}
      #(is (= ::rooms/fork-grant-cleanup-pending
              (try (rooms/drop-fork-grant! {:scope :fixture}) nil
                   (catch Exception error (:type (ex-data error)))))))
    (with-redefs-fn
      {#'rooms/data-cfg (constantly cfg)
       #'substrate/delete-database! (fn [_] (throw (ex-info "Backend refused" {})))}
      #(is (thrown-with-msg? Exception #"Backend refused"
                             (rooms/drop-fork-grant! {:scope :fixture}))))))
