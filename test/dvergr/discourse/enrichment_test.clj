(ns dvergr.discourse.enrichment-test
  "Tests for dvergr.discourse.enrichment — on-message wrappers.

   After the rooms unification, only two wrappers remain:
   with-self-filter and with-silence. The room-context and
   intel-routing wrappers are gone — per-room Participants make them
   unnecessary."
  (:require [clojure.test :refer [deftest is testing are]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.discourse :as d]
            [dvergr.discourse.enrichment :as enr]
            [org.replikativ.spindel.engine.impl.simple :as engine]))

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

;; (self-filter's :source-event path was removed along with the engine's
;; with-sources/:source drivers — scheduled/external work now arrives as
;; ordinary Messages, covered by the self-authored-Message tests below.)

(deftest self-filter-passes-non-source-events
  (testing "A Message from someone ELSE passes through"
    (let [r      (d/room :t)
          events (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (recording-participant :a events nil)
                      (enr/with-self-filter))))
      (d/post! r (d/message :driver :a "hello"))
      (engine/await-drain-complete! (:ctx r) :timeout-ms 100)
      (is (= 1 (count @events)) "inbox message delivered"))))

(deftest self-filter-drops-self-authored-messages
  (testing "A participant's OWN Message — addressed back to it OR broadcast —
            is dropped (the echo-loop that hit the forked room). Messages from
            others still arrive."
    (let [r      (d/room :t)
          events (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (recording-participant :a events nil)
                      (enr/with-self-filter))))
      (d/post! r (d/message :a :a   "my own echo"))       ; addressed back to self
      (d/post! r (d/message :a nil  "my own broadcast"))  ; broadcast (:to nil) from self
      (d/post! r (d/message :b :a   "from b"))            ; from someone else
      (engine/await-drain-complete! (:ctx r) :timeout-ms 150)
      (is (= 1 (count @events)) "only the non-self message is delivered")
      (is (= :b (:from (first @events)))))))

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

(deftest silent-reply-recognizes-variants
  (testing "the canonical [SKIP] plus tolerated spellings count as silence"
    (are [content] (true? (enr/silent-reply? content))
      ""  "   "  nil
      "[SKIP]"  "[skip]"  "  [SKIP] nothing to add"
      "SKIP"  "skip"  "NO_REPLY"  "no_reply"  "[NO_REPLY]"  "NOOP"  "no-op")
    (are [content] (false? (enr/silent-reply? content))
      "real reply"
      "I'll skip the tests for now"     ; 'skip' as a word, not the signal
      "skipping ahead")))

(deftest strip-context-annotation-removes-leaked-prefix
  (testing "a leaked [name · time] prefix is stripped; real content is untouched"
    (are [in out] (= out (enr/strip-context-annotation in))
      "[var · 15:55]\nDoing well, thanks!" "Doing well, thanks!"   ; the real leak
      "[christian · 9:05] hello there"      "hello there"
      "  [scribe · 23:09]   hi"             "hi"
      "Doing well, thanks!"                 "Doing well, thanks!"   ; no prefix → unchanged
      "[note] see [1] below"                "[note] see [1] below"  ; not the annotation shape
      "[SKIP]"                              "[SKIP]")               ; silence token untouched
    (is (nil? (enr/strip-context-annotation nil)))))

;; with-room-context and with-intel-room-routing tests removed —
;; those wrappers were deleted when rooms unified with discourse.
