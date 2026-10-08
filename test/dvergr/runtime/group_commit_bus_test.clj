(ns dvergr.runtime.group-commit-bus-test
  "Group commit: concurrent posts share one durable write, publish in post
   order, are durable before they are visible, and fail one by one."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.datahike :as dhs]
            [dvergr.runtime.bus :as bus]
            [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.agent.run :as run])
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

(defn- held-store
  "A durable hook like a store's (a second write of an id is :duplicate)
   whose first write waits for `release`, so the posts after it queue into
   one batch; `fail?` marks the ids it refuses."
  [entered release fail?]
  (let [written (atom #{}) first? (atom true)
        one (fn [m] (cond (fail? (:id m)) :failed
                          (contains? @written (:id m)) :duplicate
                          :else (do (swap! written conj (:id m)) :inserted)))]
    {:durable-append! (fn [m] (when (compare-and-set! first? true false) (deliver entered true) @release) (one m))
     :durable-append-batch! (fn [msgs] (mapv one msgs))}))

(defn- post-behind-a-held-commit!
  "Post `msgs` while a first post holds the commit, so they share a batch."
  [b entered release msgs]
  (let [first-post (future (bus/post! b {:id (random-uuid) :to :x :content "first"}))]
    (deref entered 2000 nil)
    (let [latch (CountDownLatch. 1)
          fs (mapv (fn [m] (future (.await latch) (try (bus/post! b m) nil (catch Throwable t t)))) msgs)]
      (.countDown latch)
      (Thread/sleep 100)
      (deliver release true)
      @first-post
      (mapv #(deref % 5000 ::hung) fs))))

(deftest a-partly-failed-batch-publishes-what-it-wrote
  ;; the written message must not be written again: a store reads it back as
  ;; a duplicate, and it would never be published
  (let [bad (random-uuid) entered (promise) release (promise)
        b (bus/create-bus (held-store entered release #{bad}))
        good {:id (random-uuid) :to :x :content "good"}
        errors (post-behind-a-held-commit! b entered release [good {:id bad :to :x :content "bad"}])]
    (is (= 1 (count (remove nil? errors))) "only the bad post fails")
    (is (some #(= (:id good) (:id %)) (bus/log b)) "the good message is published")))

(deftest a-failed-publication-answers-every-poster
  (let [entered (promise) release (promise)
        b (bus/create-bus (held-store entered release #{}))
        boom (random-uuid)
        publish @#'bus/publish!]
    (with-redefs [bus/publish! (fn [bus msg] (if (= boom (:id msg)) (throw (ex-info "mailbox down" {})) (publish bus msg)))]
      (let [msgs (into [{:id boom :to :x :content "boom"}] (map #(hash-map :id (random-uuid) :to :x :content %) (range 5)))
            done (future (post-behind-a-held-commit! b entered release msgs))
            errors (deref done 10000 ::hung)]
        (is (not= ::hung errors) "no poster waits forever")
        (is (not-any? #{::hung} (if (vector? errors) errors [])))
        (is (= 1 (count (remove nil? errors))))
        (is (= 6 (count (bus/log b))) "the first post and the five others are published")))))

(deftest a-repeated-id-in-a-batch-is-published-once
  (let [room (d/make-room {:id (keyword (str "batch-dup-" (random-uuid))) :store (memory/make)})
        m (d/message :customer :agent "twice" nil {:role :user})]
    (try
      (d/post-batch! room [m m])
      (is (= 1 (count (filter #(= (:id m) (:id %)) (d/log room))))
          "the first occurrence is published, the repeat is not")
      (finally (d/close-room! room)))))

(deftest nested-operations-of-two-runs-refuse-even-on-one-stripe
  (let [call @#'run/call-with-run-lock
        stripe (Object.)
        [x y] (repeatedly 2 random-uuid)]
    (with-redefs-fn {#'run/run-stripe (fn [_] stripe)}
      (fn []
        (is (= :inner (call :room-a x (fn [] (call :room-a x (fn [] :inner)))))
            "the same Run again is reentrant")
        (is (thrown-with-msg? Exception #"nested inside another Run"
                              (call :room-a x (fn [] (call :room-b y (fn [] :inner)))))
            "another Run inside, sharing the stripe, is refused")))))

(deftest a-store-without-batches-keeps-what-it-wrote-before-a-throw
  ;; the per-message fallback: a later message that throws must not cost
  ;; the earlier, written ones their publication
  (let [room (d/make-room {:id (keyword (str "fallback-" (random-uuid))) :store (memory/make)})
        good (d/message :customer :agent "good" nil {:role :user})
        bad (d/message :customer :agent "bad" nil {:role :user :not-a-modelled-key true})]
    (try
      (is (thrown? Exception (d/post-batch! room [good bad])))
      (is (= [(:id good)] (mapv :id (filter #(#{(:id good) (:id bad)} (:id %)) (d/log room))))
          "the written message is published, the refused one is not")
      (finally (d/close-room! room)))))
