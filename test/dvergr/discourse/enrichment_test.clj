(ns dvergr.discourse.enrichment-test
  "Tests for dvergr.discourse.enrichment — on-message wrappers.

   Three of the four wrappers are pure (self-filter, silence, room-context
   stub) and tested directly. with-intel-room-routing depends on
   dvergr.rooms (Datahike), so we test it with a redef stub for conn/rooms."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.discourse :as d]
            [dvergr.discourse.enrichment :as enr]
            [dvergr.rooms :as rooms]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- await-spin
  ([room spin-fn] (await-spin room spin-fn 3000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

(defn- recording-participant
  "Participant that records every envelope to `events` and either replies
   with the constant `reply-content` (or nil for no reply)."
  [id events reply-content]
  (d/participant
    {:id id
     :on-message
     (fn [_p env]
       (sp/spin
         (swap! events conj env)
         (when reply-content
           {:to (or (:from env) :anyone) :content reply-content})))}))

;; ============================================================================
;; with-self-filter
;; ============================================================================

(deftest self-filter-drops-source-events-from-self
  (testing "Source events authored by this participant are dropped"
    (let [r      (d/room :t)
          events (atom [])
          src    (binding [ec/*execution-context* (:ctx r)]
                   (sync/mailbox))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (recording-participant :a events nil)
                      (enr/with-self-filter)
                      (d/with-sources [{:name :feed :source src}]))))
      ;; Post two source events: one authored by :a (should drop), one by :b
      (binding [ec/*execution-context* (:ctx r)]
        (sync/post! src {:source-agent-id "a" :payload "self"})
        (sync/post! src {:source-agent-id "b" :payload "other"}))
      (Thread/sleep 200)
      (is (= 1 (count @events)) "self-authored event dropped")
      (is (= "other" (get-in (first @events) [:msg :payload]))))))

(deftest self-filter-passes-non-source-events
  (testing "Self-filter only inspects :source events; messages pass through"
    (let [r      (d/room :t)
          events (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (recording-participant :a events nil)
                      (enr/with-self-filter))))
      (d/post! r (d/message :driver :a "hello"))
      (Thread/sleep 100)
      (is (= 1 (count @events)) "inbox message delivered"))))

;; ============================================================================
;; with-silence
;; ============================================================================

(deftest silence-drops-skip-and-blank-replies
  (testing "[SKIP]-prefixed and blank replies are filtered to nil"
    (let [r (d/room :t)]
      (binding [ec/*execution-context* (:ctx r)]
        ;; Participant whose reply depends on the input content
        (d/join r (-> (d/participant
                        {:id :a
                         :on-message
                         (fn [_p msg]
                           (sp/spin
                             (case (:content msg)
                               "skip" {:to (:from msg) :content "[SKIP] nothing"}
                               "blank" {:to (:from msg) :content "   "}
                               "real" {:to (:from msg) :content "real reply"}
                               nil)))})
                      (enr/with-silence))))
      (let [r1 (await-spin r #(d/ask % :a {:content "real"}) 1000)
            r2 (await-spin r #(d/ask % :a {:content "skip"}) 500)
            r3 (await-spin r #(d/ask % :a {:content "blank"}) 500)]
        (is (= "real reply" (:content r1)))
        (is (= ::timeout r2) "[SKIP] reply suppressed — ask never resolves")
        (is (= ::timeout r3) "blank reply suppressed — ask never resolves")))))

;; ============================================================================
;; with-room-context
;; ============================================================================

(deftest room-context-prepends-history
  (testing "Inbox message gets room history prepended to content"
    (let [r        (d/room :t)
          captured (atom nil)]
      ;; Stub out rooms/get-messages so we don't need a real Datahike conn
      (with-redefs [rooms/get-messages
                    (fn [_conn _rid & _opts]
                      [{:role :user :content "earlier-1"}
                       {:role :assistant :content "earlier-2"}])]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (-> (d/participant
                          {:id :a
                           :on-message
                           (fn [_p env]
                             (sp/spin
                               (reset! captured (:content env))
                               {:to (:from env) :content "ack"}))})
                        (enr/with-room-context {:conn :stub
                                                :room-ids [:room-1]}))))
        (d/post! r (d/message :driver :a "new message"))
        (Thread/sleep 150)
        (let [c @captured]
          (is (string? c))
          (is (re-find #"earlier-1" c))
          (is (re-find #"earlier-2" c))
          (is (re-find #"new message" c)
              "original message body still present after prepend"))))))

(deftest room-context-passes-driver-events-untouched
  (testing "Tick / source envelopes don't get the history prepend"
    (let [r        (d/room :t)
          captured (atom nil)]
      (with-redefs [rooms/get-messages
                    (fn [_conn _rid & _opts]
                      [{:role :user :content "history-A"}])]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (-> (d/participant
                          {:id :a
                           :on-message
                           (fn [_p env]
                             (sp/spin
                               (reset! captured env)
                               nil))})
                        (enr/with-room-context {:conn :stub
                                                :room-ids [:room-1]})
                        (d/with-cadence 60))))
        (Thread/sleep 150)
        (let [c @captured]
          (is (= :tick (:type c)) "tick envelope passed through unmodified"))))))

;; ============================================================================
;; with-intel-room-routing
;; ============================================================================

(deftest intel-routing-posts-reply-to-source-room
  (testing "Source-triggered reply is posted back to that room via rooms"
    (let [r        (d/room :t)
          posted   (atom [])
          src      (binding [ec/*execution-context* (:ctx r)]
                     (sync/mailbox))]
      (with-redefs [rooms/get-room-by-slug
                    (fn [_conn slug] {:chat/id (str "chat-" slug)})
                    rooms/post-message!
                    (fn [_conn chat-id msg]
                      (swap! posted conj {:chat chat-id :msg msg}))]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (-> (d/participant
                          {:id :a
                           :on-message
                           (fn [_p env]
                             (sp/spin
                               (when (= :source (:type env))
                                 {:to :elsewhere
                                  :content "this is a substantial intel reply with enough length"})))})
                        (enr/with-intel-room-routing {:conn :stub})
                        (d/with-sources [{:name :market :source src}]))))
        (binding [ec/*execution-context* (:ctx r)]
          (sync/post! src {:source-agent-id "external" :note "x"}))
        (Thread/sleep 200)
        (is (= 1 (count @posted)))
        (is (= "chat-market" (:chat (first @posted))))
        (is (re-find #"substantial intel"
                     (:content (:msg (first @posted)))))))))

(deftest intel-routing-skips-short-and-skip-replies
  (testing "Short replies and [SKIP] replies are not posted to the room"
    (let [r       (d/room :t)
          posted  (atom [])
          src     (binding [ec/*execution-context* (:ctx r)]
                    (sync/mailbox))]
      (with-redefs [rooms/get-room-by-slug
                    (fn [_conn slug] {:chat/id (str "chat-" slug)})
                    rooms/post-message!
                    (fn [_conn chat-id msg]
                      (swap! posted conj {:chat chat-id :msg msg}))]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (-> (d/participant
                          {:id :a
                           :on-message
                           (fn [_p env]
                             (sp/spin
                               (when (= :source (:type env))
                                 (case (get-in env [:msg :which])
                                   :short {:to :_ :content "too short"}
                                   :skip {:to :_ :content "[SKIP] no opinion"}
                                   nil))))})
                        (enr/with-intel-room-routing {:conn :stub})
                        (d/with-sources [{:name :market :source src}]))))
        (binding [ec/*execution-context* (:ctx r)]
          (sync/post! src {:which :short})
          (sync/post! src {:which :skip}))
        (Thread/sleep 200)
        (is (zero? (count @posted))
            "neither short nor [SKIP] reply got persisted")))))
