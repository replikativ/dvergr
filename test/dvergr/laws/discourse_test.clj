(ns dvergr.laws.discourse-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dvergr.discourse :as discourse]
            [dvergr.discourse-support :as t]
            [dvergr.room :as room]
            [dvergr.schema :as schema]
            [dvergr.value :as value]
            [dvergr.store :as store]
            [dvergr.test-support :as base]
            [dvergr.world.systems :as systems]))

(deftest d1-posting-command-model
  "D1: generated insert/retry/conflict commands preserve one canonical durable envelope and never replay retries."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r)]
        (is (:pass?
             (tc/quick-check
              15
              (prop/for-all [commands (gen/vector (gen/tuple (gen/choose 0 3) (gen/elements [:post :retry :conflict])) 1 12)]
                            (let [ids (vec (repeatedly 4 random-uuid)) model (atom {})]
                              (every?
                               true?
                               (for [[slot command] commands]
                                 (let [id (ids slot) previous (get @model id)
                                       spec (cond-> (t/message id) (= command :conflict) (assoc :body {:text "changed"}))]
                                   (if (and previous (not= spec (:spec previous)))
                                     (= :identity-conflict (base/error-code #(discourse/post! i v a spec)))
                                     (let [result (discourse/post! i v a spec)]
                                       (swap! model assoc id {:spec spec :envelope (:envelope result)})
                                       (and (= (if previous :existing :inserted) (:persistence result))
                                            (or (nil? previous) (= :not-offered (:live-publication result)))
                                            (= (:envelope result)
                                               (some #(when (= id (:message/id %)) %) (:messages (discourse/history i v {:limit 256})))))))))))))))))))

(deftest d4-shared-world-and-fixed-parent-history
  "D4/history: branching shares world and handles while pagination and inherited parent basis remain fixed."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) first-message (discourse/post! i v a (t/message))
            page (discourse/history i v {:limit 1})
            branch (discourse/branch! i v {:id (random-uuid)})]
        (discourse/post! i v a (t/message))
        (discourse/post! i branch a (t/message))
        (let [id (random-uuid)]
          (room/work! i v :discourse/write {:command :decision :record {:decision/id id :projection/id (:projection/id v)
                                                                        :participant/id (random-uuid) :version 0}})
          (room/work! i branch :discourse/write {:command :decider :id id :expected-version 0 :participant-id (random-uuid)})
          (is (= 1 (:version (room/work! i v :discourse/read {:command :decision :id id})))))
        (is (identical? (:world (:room branch)) (:world r)))
        (is (= (systems/handle-data (:system v)) (systems/handle-data (:system branch))))
        (is (= 2 (count (:messages (discourse/history i branch {})))))
        (is (= (:envelope first-message) (first (:messages page))))
        (let [page (discourse/history i v {:limit 1})]
          (discourse/post! i v a (t/message))
          (is (= 1 (count (:messages (discourse/history i v {:limit 1 :cursor (:cursor page)}))))))))))

(deftest d5-control-separation-and-egress-refusal
  "D5: reserved shapes refuse and every unsupported destination refuses before a delivery executor."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) before (store/room-record (:control i) (get-in r [:record :room/id]))]
        (is (:pass?
             (tc/quick-check
              30
              (prop/for-all [destination (gen/elements [:external :parent :sibling :unknown :human])
                             reserved (gen/elements [:control/terminal :settlement/receipt :receipt/id :admission/accepted])]
                            (and (= :effect-boundary-unavailable
                                    (base/error-code #(discourse/post! i v a (assoc (t/message) :destination destination))))
                                 (= :reserved-control-kind
                                    (base/error-code #(discourse/post! i v a (assoc (t/message) :type reserved)))))))))
        (is (= before (store/room-record (:control i) (get-in r [:record :room/id]))))
        (is (empty? (:messages (discourse/history i v {}))))))))

(deftest d1-history-independent-origins-and-fixed-pagination
  "D1/history: independent-origin insertion orders produce stable total keys; fixed-basis pagination returns every identity exactly once."
  (t/with-room
    (fn [i r v]
      (is (:pass?
           (tc/quick-check
            12
            (prop/for-all [origins (gen/vector (gen/choose 0 2) 1 8)]
                          (let [branch (discourse/branch! i v {:id (random-uuid)})
                                worlds (vec (repeatedly 3 #(keyword (str "root-" (random-uuid)))))
                                identities (vec (repeatedly (count origins) random-uuid))
                                author {:principal/id (random-uuid) :participant/id (random-uuid)}]
                            (doseq [[origin id] (map vector origins identities)]
                              (room/work! i branch :discourse/write
                                          {:command :post :id id :origin (worlds origin)
                                           :canonical {:request (t/message id) :author author
                                                       :room/id (get-in r [:record :room/id]) :projection/id (:projection/id branch)}}))
                            (loop [page (discourse/history i branch {:limit 2}) seen []]
                              (let [seen (into seen (:messages page))]
                                (if-let [cursor (:cursor page)]
                                  (recur (discourse/history i branch {:limit 2 :cursor cursor}) seen)
                                  (and (= (set identities) (set (map :message/id seen)))
                                       (= (count identities) (count seen))
                                       (= (map :order/key seen) (sort (map :order/key seen)))))))))))))))

(deftest d1-w1-history-cursors-cannot-read-child-or-sibling
  "D1/W1: a parent cannot read a child's unmerged history using its cursor; sibling, foreign and unknown bases refuse."
  (t/with-work
    (fn [i root v room-id]
      (let [post (fn [] (let [m (t/message)]
                          (room/work! i v :discourse/write
                                      {:command :post :id (:id m) :origin (get-in root [:descriptor :world/id])
                                       :canonical {:request m :author {:principal/id (random-uuid) :participant/id (random-uuid)}
                                                   :room/id room-id :projection/id (:projection/id v)}})))
            _ (dotimes [_ 2] (post))
            _ (post)
            child (base/frozen! i root)
            sibling (base/frozen! i root)
            cursor-for (fn [w]
                         (binding [org.replikativ.spindel.engine.core/*execution-context* (:context w)]
                           (post)
                           (:cursor (room/work! i v :discourse/read {:command :history :id (:projection/id v) :limit 1}))))
            child-cursor (cursor-for child)
            sibling-cursor (cursor-for sibling)
            foreign (dvergr.world/create! i (base/assembly [(t/spec (t/connection))]))
            foreign-basis (binding [org.replikativ.spindel.engine.core/*execution-context* (:context foreign)]
                            (yggdrasil.protocols/snapshot-id
                             (systems/resolve! i (systems/handle i foreign :messages #{:discourse/read}) :discourse/read)))]
        (binding [org.replikativ.spindel.engine.core/*execution-context* (:context child)]
          (is (= 3 (count (:messages (room/work! i v :discourse/read
                                                 {:command :history :id (:projection/id v) :basis (:basis child-cursor)
                                                  :after (:after child-cursor) :limit 256}))))))
        (doseq [cursor [child-cursor sibling-cursor
                        (assoc child-cursor :basis foreign-basis)
                        (assoc child-cursor :basis (str (random-uuid)))]]
          (is (= :invalid-cursor
                 (base/error-code #(room/work! i v :discourse/read
                                               {:command :history :id (:projection/id v) :basis (:basis cursor)
                                                :after (:after cursor) :limit 1})))))
        (binding [org.replikativ.spindel.engine.core/*execution-context* (:context sibling)]
          (is (= :invalid-cursor
                 (base/error-code #(room/work! i v :discourse/read
                                               {:command :history :id (:projection/id v) :basis (:basis child-cursor)
                                                :after (:after child-cursor) :limit 1})))))))))

(deftest d1-history-pages-budget-aggregate-envelopes
  "D1: two individually valid large envelopes paginate at limits 1 and 256; an envelope that cannot fit a singleton page refuses before commit."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r)
            large #(assoc (t/message) :body {:items (vec (repeat 26000 0))})
            expected (mapv :envelope [(discourse/post! i v a (large)) (discourse/post! i v a (large))])]
        (doseq [limit [1 256]]
          (let [one (discourse/history i v {:limit limit})
                two (discourse/history i v {:limit limit :cursor (:cursor one)})]
            (is (= expected (into (:messages one) (:messages two))))
            (is (= 1 (count (:messages one))))
            (is (nil? (:cursor two)))))
        ;; The request and post result fit; continuation metadata tips the singleton page over budget.
        (let [m (assoc (t/message) :body {:items (vec (repeat 49946 0))})
              result {:envelope (assoc (first expected) :body (:body m))
                      :persistence :inserted :live-publication :not-offered}]
          (is (= result (value/portable! result schema/limits)))
          (is (= :value-limit (base/error-code #(discourse/post! i v a m))))
          (is (nil? (room/work! i v :discourse/read {:command :message :id (:id m)}))))))))
