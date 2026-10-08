(ns dvergr.runtime.group-commit-bus-test
  "Group commit: concurrent posts share one durable write, publish in post
   order, are durable before they are visible, and fail one by one."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.datahike :as dhs]
            [dvergr.runtime.bus :as bus]
            [datahike.api :as dh]
            [dvergr.chat.schema :as schema])
  (:import [java.util.concurrent CountDownLatch]))

(defn- post-at-once!
  "Post `msgs` from one thread each, released together; the thrown error or
   nil per message."
  [b msgs]
  (let [latch (CountDownLatch. 1)
        fs (mapv (fn [m] (future (.await latch) (try (bus/post! b m) nil (catch Throwable t t)))) msgs)]
    (.countDown latch)
    (mapv deref fs)))

(deftest concurrent-posts-share-durable-writes-in-post-order
  (let [batches (atom [])
        durable (atom [])
        visible-early (atom 0)
        b (atom nil)
        hook (fn [msgs]
               ;; nothing of this batch is on the log yet: durable first
               (swap! visible-early + (count (filter (set (map :id msgs)) (map :id (bus/log @b)))))
               (Thread/sleep 20)
               (swap! batches conj (count msgs))
               (swap! durable into (map :id msgs))
               (mapv (constantly :inserted) msgs))]
    (reset! b (bus/create-bus {:durable-append! (fn [m] (first (hook [m])))
                               :durable-append-batch! hook}))
    (let [msgs (mapv #(hash-map :id (random-uuid) :to :x :content %) (range 32))
          errors (post-at-once! @b msgs)]
      (is (every? nil? errors))
      (is (= 32 (count (bus/log @b))) "every post is on the log once post! returns")
      (is (= @durable (mapv :id (bus/log @b))) "log order is durable order")
      (is (zero? @visible-early) "no message was visible before its durable write")
      (is (< (count @batches) 32) "concurrent posts shared durable writes")
      (is (some #(> % 1) @batches)))))

(deftest a-failing-message-fails-only-its-post
  (let [bad (random-uuid)
        hook1 (fn [m] (if (= bad (:id m)) :failed :inserted))
        b (bus/create-bus {:durable-append! hook1
                           :durable-append-batch! (fn [msgs] (Thread/sleep 20) (mapv hook1 msgs))})
        msgs (into [{:id bad :to :x :content "bad"}]
                   (map #(hash-map :id (random-uuid) :to :x :content %) (range 7)))
        errors (post-at-once! b msgs)]
    (is (instance? Throwable (first errors)) "the bad message's post fails")
    (is (every? nil? (rest errors)) "the others are published")
    (is (= 7 (count (bus/log b))))
    (is (not-any? #(= bad (:id %)) (bus/log b)))))

(deftest quiesce-waits-for-posts-in-flight
  (let [entered (promise) release (promise)
        b (bus/create-bus {:durable-append! (fn [_] (deliver entered true) @release :inserted)})
        posted (future (bus/post! b {:to :x :content "slow"}))]
    (is (true? (deref entered 2000 false)))
    (let [q (future (bus/quiesce! b) (count (bus/log b)))]
      (Thread/sleep 50)
      (is (not (realized? q)) "quiesce! waits while the post is being written")
      (deliver release true)
      @posted
      (is (= 1 (deref q 2000 ::timeout)) "and returns once the post is on the log"))))

(deftest a-store-writes-a-batch-in-one-transaction
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        _ (schema/ensure-full-schema! conn)
        st (dhs/make conn nil)
        room-id :batch-room
        [a b c] (repeatedly 3 random-uuid)
        msg (fn [id] {:id id :from :customer :to :agent :content (str id)})]
    (try
      (rstore/-store-room! st room-id {:slug "batch-room" :title "T"})
      (rstore/-store-message! st room-id (msg a))
      (let [tx (:max-tx @conn)]
        (is (= [:duplicate :inserted :inserted :duplicate]
               (rstore/store-messages! st room-id [(msg a) (msg b) (msg c) (msg b)]))
            "an existing id and a repeated one are duplicates")
        (is (= (inc tx) (:max-tx @conn)) "one transaction for the batch"))
      (is (= #{(str a) (str b) (str c)}
             (set (map :content (rstore/-list-messages st room-id {})))))
      (finally (dh/release conn)))))
