(ns dvergr.process.live-test
  "Production-path fault boundaries, followed by a real writer JVM exit."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.adapters.live-test :as live]
            [dvergr.discourse :as discourse]
            [dvergr.discourse-support :as t]
            [dvergr.participant :as participant]
            [dvergr.room :as room]
            [dvergr.store :as store]
            [dvergr.test-support :as base]
            [dvergr.world :as world]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [yggdrasil.adapters.datahike :as dh]))

(defn- interrupted [& _] (throw (ex-info "crash boundary" {:dvergr/error :storage-failure})))

(defn- prepare! [directory]
  (.mkdirs (io/file directory))
  (let [config (assoc (base/control-config) :test? false
                      :database {:store {:backend :file :id (random-uuid) :path (str directory "/live-control")}})
        work-config {:store {:backend :file :id (random-uuid) :path (str directory "/live-messages")}}
        control (store/open! config) i (world/installation control) conn (t/connection work-config)
        r (room/create! i {:id (random-uuid) :kind :root :assembly (base/assembly [(t/spec conn)])
                           :discourse {:system :messages :projection (random-uuid) :retention :indefinite}})]
    (binding [ec/*execution-context* (:context (:world r))]
      (let [v (room/venue i r (get-in r [:record :room/discourse :projection])) a (t/author i r)
            target (random-uuid) obligations (atom {})
            session (participant/join! i v (t/author i r target)
                                       {:handler (fn [_ e o] (swap! obligations assoc (:ask/id e) o) nil)})
            requests (into {} (map #(vector % (live/request target (+ (System/currentTimeMillis) 3600000))))
                           [:intent :request :reply :terminal :cleanup])
            put-ask store/put-ask! persist discourse/persist!]
        (with-redefs [store/put-ask! (fn [& args] (apply put-ask args) (interrupted))]
          (is (= :storage-failure (base/error-code #(discourse/ask i v a (:intent requests))))))
        (with-redefs [discourse/persist! (fn [& args] (apply persist args) (interrupted))]
          (is (= :storage-failure (base/error-code #(discourse/ask i v a (:request requests))))))
        (let [asked (discourse/ask i v a (:reply requests))]
          (is (live/eventually #(contains? @obligations (:id asked))))
          (with-redefs [store/terminal! interrupted]
            (is (= :storage-failure
                   (base/error-code #(participant/reply! i session (get @obligations (:id asked)) (t/message)))))))
        (let [asked (discourse/ask i v a (:terminal requests))]
          (with-redefs [sync/deliver! interrupted]
            (is (= :storage-failure (base/error-code #(discourse/cancel! i (:owner asked) :terminal-boundary))))))
        (let [asked (discourse/ask i v a (:cleanup requests))]
          (is (= {:status :cancelled :reason :cleanup-boundary}
                 (discourse/cancel! i (:owner asked) :cleanup-boundary))))
        (let [evidence {:config config :work-config work-config :room (:record r) :requests requests
                        :records (mapv #(store/ask-record control (:id %)) (vals requests))
                        :messages (:messages (discourse/history i v {})) :writer-process (base/process-identity)}]
          (spit (io/file directory "live.edn") (pr-str evidence))
          ;; Deliberately leave live owners unresolved. This writer JVM exits;
          ;; the reader must recover without rebuilding a world or handler.
          (d/release conn) (store/close! control)
          evidence)))))

(defn -main [directory] (prepare! directory) (shutdown-agents))

(deftest d3-process-restart-at-five-commit-and-cleanup-boundaries
  "D3: restart at intent, request commit, reply commit, terminal commit and cleanup; retain winners, end lost sessions, preserve history and never replay handlers."
  (let [directory (System/getenv "DVERGR_PROCESS_EVIDENCE")]
    (is (some? directory) "Run the sequential process writers before the suite")
    (when directory
      (let [{:keys [config work-config room requests records messages writer-process]}
            (edn/read-string (slurp (io/file directory "live.edn")))
            control (store/open! config) i (world/installation control)
            conn (d/connect (merge {:schema-flexibility :write :keep-history? true :commit-graph? true
                                    :writer {:backend :self :writer-ownership :exclusive}} work-config))]
        (try
          (is (not= writer-process (base/process-identity)))
          (is (= records (mapv #(store/ask-record control (:id %)) (vals requests))))
          (is (= 3 (count (:reconciled (discourse/recover! i room)))))
          (is (empty? (:reconciled (discourse/recover! i room))))
          (doseq [boundary [:intent :request :reply]]
            (is (= {:status :leave :reason :process-lost}
                   (discourse/resolution control (:id (get requests boundary))))))
          (doseq [boundary [:terminal :cleanup]]
            (is (= {:status :cancelled :reason (keyword (str (name boundary) "-boundary"))}
                   (discourse/resolution control (:id (get requests boundary))))))
          (let [page (store/work! (dh/create conn) :discourse/read
                                  {:command :history :id (get-in room [:room/discourse :projection]) :limit 256})]
            (is (= messages (:messages page)))
            (is (= 5 (count messages))))
          (is (empty? @(:live i))) (is (empty? @(:owners i)))
          (finally (d/release conn) (store/close! control)))))))
