(ns dvergr.clients.owned-shutdown-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.clients.client :as client]
            [dvergr.discourse :as discourse]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest lite-shutdown-retains-its-root-until-release-acknowledges
  (let [root (context/create-execution-context)
        lite (atom root) current (atom {:execution-ctx root})
        instance @current
        failure (ex-info "Store owner still held" {})
        held? (atom true) releases (atom 0) closed (atom 0)]
    (try
      (with-redefs-fn
        {#'client/!lite-ctx lite #'daemon/current-daemon current
         #'discourse/close-registered-rooms! (fn [ctx] (is (identical? root ctx)) ::plan)
         #'discourse/close-shutdown-contexts! (fn [plan] (is (= ::plan plan)) (swap! closed inc))
         #'rooms/release-room-stores!
         (fn []
           (is (identical? root ec/*execution-context*))
           (swap! releases inc)
           (when @held? (throw failure)))}
        (fn []
          (is (identical? failure (try (client/stop!) nil (catch Throwable error error))))
          (is (identical? root @lite))
          (is (identical? instance @current))
          (is (zero? @closed))
          (reset! held? false)
          (is (= :stopped (client/stop!)))
          (is (nil? @lite))
          (is (nil? @current))
          (is (= 2 @releases))
          (is (= 1 @closed))))
      (finally (context/close-context! root)))))
