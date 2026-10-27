(ns dvergr.process.discourse-test
  "Sequential writer/reopen evidence for retained discourse."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.discourse :as discourse]
            [dvergr.discourse-support :as t]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.test-support :as base]
            [dvergr.world :as world]
            [org.replikativ.spindel.engine.core :as ec]
            [yggdrasil.adapters.datahike :as dh]))

(defn- prepare! [directory]
  (.mkdirs (io/file directory))
  (let [config (assoc (base/control-config) :test? false
                      :database {:store {:backend :file :id (random-uuid) :path (str directory "/discourse-control")}})
        work-config {:store {:backend :file :id (random-uuid) :path (str directory "/messages")}}
        control (store/open! config) i (world/installation control)
        conn (t/connection work-config)
        r (room/create! i {:id (random-uuid) :kind :root :assembly (base/assembly [(t/spec conn)])
                           :discourse {:system :messages :projection (random-uuid) :retention :indefinite}})]
    (try
      (binding [ec/*execution-context* (:context (:world r))]
        (let [v (room/venue i r (get-in r [:record :room/discourse :projection])) a (t/author i r)
              one (discourse/post! i v a (t/message))
              branch (discourse/branch! i v {:id (random-uuid)})
              _ (discourse/post! i v a (t/message))
              two (discourse/post! i branch a (t/message))
              page (discourse/history i branch {:limit 1})
              evidence {:config config :work-config work-config :room (:record r)
                        :projection (:projection/id branch) :basis (:basis page)
                        :messages [(:envelope one) (:envelope two)] :page page
                        :writer-process (base/process-identity)}]
          (is (= {:status :closed} (t/await! (room/close! i r))))
          (spit (io/file directory "discourse.edn") (pr-str evidence))
          (world/close! i (:world r))
          evidence))
      (finally (store/close! control)))))

(defn -main [directory]
  (prepare! directory)
  (shutdown-agents))

(deftest d1-d4-process-history
  "D1/D4: a separate writer preserves exact envelopes, order, page series and projection bases."
  (let [external (System/getenv "DVERGR_PROCESS_EVIDENCE")
        directory (or external (str (java.nio.file.Files/createTempDirectory
                                     "dvergr-discourse-" (make-array java.nio.file.attribute.FileAttribute 0))))
        evidence (if external (edn/read-string (slurp (io/file directory "discourse.edn"))) (prepare! directory))
        {:keys [config work-config room projection basis messages page writer-process]} evidence
        control (store/open! config) i (world/installation control)
        conn (d/connect (merge {:schema-flexibility :write :keep-history? true :commit-graph? true
                                :writer {:backend :self :writer-ownership :exclusive}} work-config))]
    (try
      (when external (is (not= writer-process (base/process-identity))))
      (is (= room (room/lookup control (:room/id room))))
      (let [system (dh/create conn)
            next-page (store/work! system :discourse/read
                                   {:command :history :id projection :basis basis
                                    :after (get-in page [:cursor :after]) :limit 256})]
        (is (= [(second messages)] (:messages next-page)))
        (is (= [(first messages)] (:messages page)))
        (is (nil? (:cursor next-page))))
      (is (empty? @(:live i)))
      (finally (d/release conn) (store/close! control)))))
