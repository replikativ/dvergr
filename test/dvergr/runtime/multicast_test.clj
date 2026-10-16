(ns dvergr.runtime.multicast-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.bus :as bus]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest one-durable-envelope-reaches-every-selected-inbox-once
  (let [room (d/make-room {:id (keyword (str "multicast-" (random-uuid)))
                           :store (memory/make)})
        message (d/with-recipients (d/message :human nil "work") [:a :b :a])
        seen (atom {})
        labels [:a :b :outsider nil :typed]
        barriers (zipmap labels (repeatedly promise))
        subs (mapv #(bus/subscribe! (:bus room)
                                    (if (= :typed %) [:type :user/message] [:to %])) labels)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (doseq [[label sub] (map vector labels subs)]
          (sp/spawn!
           (sp/spin
            (loop [source (:aseq sub)]
              (when-let [[msg more] (sp/await (aseq/anext source))]
                (cond
                  (= (:id message) (:id msg)) (swap! seen update label (fnil conj []) msg)
                  (= "barrier" (:content msg)) (deliver (get barriers label) true))
                (recur more)))))))
      (let [posted (d/post! room message)]
        (d/post! room message)
        (d/post! room (d/with-recipients message [:outsider]))
        ;; Each FIFO inbox observes a barrier after both posts. Negative
        ;; assertions do not depend on sleeping for an assumed delivery time.
        (doseq [target [:a :b :outsider nil]]
          (d/post! room (d/message :human target "barrier")))
        (doseq [barrier (vals barriers)]
          (is (true? (deref barrier 10000 ::timeout))))
        (is (= #{:a :b} (get-in message [:metadata :dvergr/recipients])))
        (is (= [posted] (get @seen :a)))
        (is (= [posted] (get @seen :b)))
        (is (= [posted] (get @seen :typed)))
        (is (empty? (get @seen :outsider)))
        (is (empty? (get @seen nil)))
        (is (= 1 (count (filter #(= (:id message) (:id %)) (d/messages room)))))
        (is (= 1 (count (filter #(= (:id message) (:id %)) (bus/log (:bus room)))))))
      (finally
        (doseq [sub subs] (bus/unsubscribe! sub))
        (d/close-room! room)))))

(deftest malformed-multicast-is-refused-before-durability
  (let [room (d/make-room {:id (keyword (str "bad-multicast-" (random-uuid)))
                           :store (memory/make)})]
    (try
      (doseq [targets [#{} #{:a nil} [:a :b] "a" #{:a "b"}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (d/post! room (d/message :human nil "bad" nil
                                              {:dvergr/recipients targets})))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (d/post! room (d/message :human :a "ambiguous" nil
                                            {:dvergr/recipients #{:b}}))))
      (is (empty? (d/messages room)))
      (is (empty? (bus/log (:bus room))))
      (finally (d/close-room! room)))))

(deftest routing-view-does-not-share-a-mutable-transducer-cursor
  (let [message {:to nil :metadata {:dvergr/recipients #{:a :b}}}
        routes (bus/->RecipientRoutes [message] nil)
        pull (fn [source]
               (let [result (promise)]
                 ((aseq/anext source) #(deliver result %) #(deliver result %))
                 (deref result 1000 ::timeout)))
        [first-a rest-a] (pull routes)
        [first-b rest-b] (pull routes)]
    (is (= :a (::bus/target first-a) (::bus/target first-b)))
    (is (= :b (::bus/target (first (pull rest-a)))
           (::bus/target (first (pull rest-b)))))
    (is (identical? message (::bus/message first-a)))
    (let [[unwrapped more] (pull (bus/->Envelopes [{::bus/message message}]))]
      (is (identical? message unwrapped))
      (is (nil? (pull more))))))
