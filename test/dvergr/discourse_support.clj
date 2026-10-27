(ns dvergr.discourse-support
  (:require [clojure.test :refer [is]]
            [datahike.api :as d]
            [dvergr.discourse :as discourse]
            [dvergr.room :as room]
            [dvergr.schema :as schema]
            [dvergr.test-support :as t]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.spin.cps :refer [spin]]))

(defn connection
  ([] (connection {}))
  ([config]
   (let [conn (t/work-connection config)] (d/transact conn (schema/discourse-schema)) conn)))

(defn spec [conn]
  (assoc (t/system-spec :messages conn) :operations #{:discourse/read :discourse/write}
         :attributes schema/discourse-attributes))

(defn create! [i]
  (room/create! i {:id (random-uuid) :kind :root :assembly (t/assembly [(spec (connection))])
                   :discourse {:system :messages :projection (random-uuid) :retention :indefinite}}))

(defn await! [x] (deref (spin (await x)) 5000 ::timeout))

(defn with-room [f]
  (t/with-installation
    (fn [i]
      (let [r (create! i)]
        (binding [ec/*execution-context* (:context (:world r))]
          (try
            (f i r (room/venue i r (get-in r [:record :room/discourse :projection])))
            (finally (is (= {:status :closed} (await! (room/close! i r)))))))))))

(defn author
  ([i r] (author i r (random-uuid)))
  ([i r participant]
   (discourse/authenticate! i r {:principal-id (random-uuid) :participant-id participant
                                 :edit-decisions? true})))

(defn message
  ([] (message (random-uuid)))
  ([id] {:id id :recipients :broadcast :type :conversation/statement :body {:text "hello"} :tags #{:test}}))

(defn with-work [f]
  (t/with-installation
    (fn [i]
      (let [root (dvergr.world/create! i (t/assembly [(spec (connection))]))
            v {:system (dvergr.world.systems/handle i root :messages #{:discourse/read :discourse/write})
               :projection/id (random-uuid)}
            room-id (random-uuid)]
        (binding [ec/*execution-context* (:context root)]
          (room/work! i v :discourse/write
                      {:command :projection :record {:projection/id (:projection/id v) :room/id room-id
                                                     :projection/parent nil :projection/basis nil}})
          (f i root v room-id))))))
