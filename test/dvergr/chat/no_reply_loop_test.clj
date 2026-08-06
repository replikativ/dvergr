(ns dvergr.chat.no-reply-loop-test
  "The loop shape `detect-doom-loop` cannot see.

   MEASURED on dev.simm.is 2026-08-06: an agent ran turns 0→15, every one a
   `clojure_eval` tool call, `content-len 0` on all of them — it never said a
   word to the room — then the cycle restarted at turn 0 and did it again.
   56,802 datahike warnings came out of the read loop it was driving.

   `detect-doom-loop` did not fire once, and correctly so: it fingerprints
   `[:tool-use/name :tool-use/input]`, so it only catches an agent repeating
   the SAME call. This agent varied its code every turn (input tokens climbed
   40,275 → 42,265). It was not repeating itself; it was never finishing.

   The only other bound is the dollar budget, which at ~40k input tokens a turn
   buys a great many turns before it bites — and it bites silently, which is
   why the room just saw nothing happen for two hours."
  (:require [clojure.test :refer [deftest testing is]]
            [dvergr.chat.agent :as agent]))

(defn- tool-turn
  "An assistant turn that called a tool and said nothing to the room.
   `n` varies the arguments, which is what makes it invisible to the
   identical-call fingerprint."
  [n]
  {:message/role :assistant
   :message/content ""
   :message/tool-uses [{:tool-use/name "clojure_eval"
                        :tool-use/input {:code (str "(wiki/read-page \"Page " n "\")")}}]})

(defn- repeated-turn
  "An assistant turn calling the SAME tool with the SAME args every time."
  []
  {:message/role :assistant
   :message/content ""
   :message/tool-uses [{:tool-use/name "clojure_eval"
                        :tool-use/input {:code "(wiki/pages)"}}]})

(defn- spoke-turn
  "An assistant turn that actually replied to the room."
  [text]
  {:message/role :assistant
   :message/content text
   :message/tool-uses []})

;; =============================================================================
;; The existing guard: what it does and does not see
;; =============================================================================

(deftest detect-doom-loop-catches-only-identical-calls
  (testing "identical calls are caught — the guard works as designed"
    (is (some? (agent/detect-doom-loop (repeat 4 (repeated-turn))))
        "three identical calls trip the fingerprint"))

  (testing "VARYING calls are invisible to it — the production shape"
    ;; This is the assertion that documents the gap. Not a bug in
    ;; detect-doom-loop; a bound that was never written.
    (is (nil? (agent/detect-doom-loop (map tool-turn (range 16))))
        "sixteen different tool calls, no reply, and nothing objects")))

;; =============================================================================
;; The bound that was missing
;; =============================================================================

(deftest silent-tool-run-is-detected
  (testing "N consecutive tool-only turns with no prose is the signal"
    (is (nil? (agent/detect-silent-tool-run (map tool-turn (range 3))))
        "a short tool run is ordinary work, not a loop")
    (is (some? (agent/detect-silent-tool-run (map tool-turn (range 20))))
        "a long one, with nothing ever said to the room, is not"))

  (testing "the count is CONSECUTIVE — speaking resets it"
    ;; An agent that reports progress is working, however many tools it uses.
    ;; Ordering is newest-last, matching how chat-ctx accumulates messages.
    (let [history (concat (map tool-turn (range 20))
                          [(spoke-turn "Here is what I found so far.")]
                          (map tool-turn (range 3)))]
      (is (nil? (agent/detect-silent-tool-run history))
          "a reply clears the streak")))

  (testing "whitespace-only content does not count as speaking"
    (let [history (concat (map tool-turn (range 10))
                          [(spoke-turn "   \n  ")]
                          (map tool-turn (range 10)))]
      (is (some? (agent/detect-silent-tool-run history))
          "an empty-looking reply is not a reply")))

  (testing "a turn with neither tools nor prose does not extend the streak"
    ;; Defensive: an empty assistant turn is a different failure, and counting
    ;; it here would make the bound fire on the wrong evidence.
    (is (nil? (agent/detect-silent-tool-run
               (repeat 20 {:message/role :assistant
                           :message/content ""
                           :message/tool-uses []})))))

  (testing "it reports how many turns went by, for the nudge to quote"
    (let [r (agent/detect-silent-tool-run (map tool-turn (range 20)))]
      (is (integer? (:turns r)))
      (is (>= (:turns r) agent/silent-tool-run-threshold)))))
