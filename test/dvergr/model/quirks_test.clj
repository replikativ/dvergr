(ns dvergr.model.quirks-test
  "Provider quirks — the defenses against models mis-addressing their tool calls.

   Every case here is a shape we have actually observed in production traffic,
   not a hypothetical. The 2026-07-13 GLM incident is the reference story: a
   model emitted its tool call as prose, our recovery ran the (mis-braced) code
   it found, the model re-emitted the repaired code as a MESSAGE, and the agent
   loop read that as 'I am done speaking' and quit mid-task."
  (:require [clojure.test :refer [deftest testing is]]
            [dvergr.model.quirks :as quirks]))

;; ---------------------------------------------------------------------------
;; code-fragment? — code fumbled into the prose channel
;; ---------------------------------------------------------------------------

(def ^:private real-spill
  "Verbatim from the room DB, 2026-07-13 16:55:47. Note the leading `)`: the
   tail of an expression whose head went into a mangled tool call."
  ")\n(def resp (http/get \"https://wttr.in/Vancouver\" {:query-params {\"format\" \"v2\"}}))\n(:body resp)")

(deftest code-fragment-catches-fumbled-code
  (testing "the real spill that ended Vár's turn"
    (is (quirks/code-fragment? real-spill)))

  (testing "a block that closes more than it opens is truncated or continued"
    (is (quirks/code-fragment? "(def x 1)))"))
    (is (quirks/code-fragment? "))")))

  (testing "content opening on a closing delimiter is never a message to a human"
    (is (quirks/code-fragment? "} :as opts]"))
    (is (quirks/code-fragment? "] (recur))")))

  (testing "a tool-call envelope surviving in content is a leak, not a reply"
    (is (quirks/code-fragment? "Tool calls: [\"clojure_eval\"] <arg_key>code</arg_key>"))
    (is (quirks/code-fragment? "<tool_call>shell"))))

(deftest code-fragment-leaves-real-replies-alone
  (testing "prose"
    (is (not (quirks/code-fragment? "The weather in Vancouver is +21°C and partly cloudy.")))
    (is (not (quirks/code-fragment? ""))))

  (testing "prose that merely contains delimiters"
    (is (not (quirks/code-fragment? "It is warm (about 21°C) today — nice for a walk!")))
    (is (not (quirks/code-fragment? "Use the map [:a :b] for that."))))

  (testing "a reply that EXPLAINS code — balanced, so not a fragment.
            This is the false positive that would matter most: suppressing it
            would silently swallow the agent's most useful answers."
    (is (not (quirks/code-fragment?
              "Here is how I fetched it:\n\n(http/get \"https://wttr.in\")\n\nThat returned +21°C.")))
    (is (not (quirks/code-fragment?
              "```clojure\n(defn add [a b] (+ a b))\n```\nCall it as (add 1 2)."))))

  (testing "emoticons are punctuation, not structure — `:)` balances to -1"
    (is (not (quirks/code-fragment? "Sounds good :) let me know")))
    (is (not (quirks/code-fragment? "haha :) (nice one)")))
    (is (not (quirks/code-fragment? "done ;)"))))

  (testing "delimiters inside strings and comments are text"
    (is (not (quirks/code-fragment? "(println \"hi :) ]]] )\")")))
    (is (not (quirks/code-fragment? "(def x 1) ; closes ) here")))))

;; ---------------------------------------------------------------------------
;; The GLM envelope: recovery must be FAITHFUL
;; ---------------------------------------------------------------------------

(deftest parse-glm-tool-calls-round-trips-code-exactly
  (testing "the parser may only SLICE — never rewrite the model's code.
            (During the 2026-07-13 incident this was the prime suspect for a
            stray brace; it was exonerated, and this test keeps it that way.)"
    (let [code (str "(require '[babashka.http-client :as http])\n"
                    "(def resp (http/get \"https://wttr.in/Vancouver\" "
                    "{:query-params {\"format\" \"v2\"}}))\n"
                    "(:body resp)")
          envelope (str "Tool calls: [\"clojure_eval\"] "
                        "<arg_key>code</arg_key><arg_value>" code "</arg_value>")
          [call] (quirks/parse-glm-tool-calls envelope)]
      (is (= "clojure_eval" (:name call)))
      (is (= code (:code (:input call)))
          "recovered code must be byte-identical to what the model emitted"))))

(deftest parse-glm-tool-calls-ignores-clean-content
  (testing "no envelope, no recovery"
    (is (nil? (quirks/parse-glm-tool-calls "Just a normal reply.")))
    (is (nil? (quirks/parse-glm-tool-calls nil)))))

(deftest sanitize-glm-structured-call-splits-name-from-leaked-envelope
  (testing "GLM leaks the envelope INTO the tool NAME; left alone it is an
            unknown tool and the invalid name poisons the next request"
    (let [call {:name "clojure_eval<arg_key>code</arg_key><arg_value>(+ 1 2)</arg_value>"
                :input nil}
          fixed (quirks/sanitize-glm-structured-call call)]
      (is (= "clojure_eval" (:name fixed)))
      (is (= "(+ 1 2)" (:code (:input fixed))))))

  (testing "a well-formed call passes through untouched"
    (let [call {:name "shell" :input {:command "ls"}}]
      (is (= call (quirks/sanitize-glm-structured-call call))))))
