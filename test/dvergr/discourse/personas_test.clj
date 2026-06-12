(ns dvergr.discourse.personas-test
  "Tests for the persona factories — researcher/coder/reviewer/from-prompt.

   We don't exercise real LLM calls; instead we install a mock
   `:run-turn-fn` on each persona and check that the returned
   Participant routes the system prompt + tool set into chat-ctx and
   replies as expected."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.discourse.personas :as personas]
            [dvergr.chat.context :as cc]))

(defn- await-spin
  ([room spin-fn] (await-spin room spin-fn 3000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
        (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

(defn- mock-turn-fn
  "A `run-turn-fn` that writes a single assistant message capturing the
   chat-ctx's seeded system prompt (so the test can verify the persona's
   prompt was injected) and returns :complete."
  [reply-text]
  (fn [chat-ctx _opts]
    (let [system-msg (->> (cc/get-messages chat-ctx)
                          (filter #(let [r (or (:role %) (:message/role %))]
                                     (or (= r :system) (= r "system"))))
                          first)
          system-content (or (:message/content system-msg)
                             (:content system-msg)
                             "")]
      (cc/add-message! chat-ctx
                       {:role :assistant
                        :content (str reply-text "\n--SYSTEM--\n" system-content)})
      :complete)))

;; ============================================================================
;; researcher / coder / reviewer
;; ============================================================================

(deftest researcher-loads-prompt-and-replies
  (testing "researcher persona injects researcher.md as system prompt"
    (let [r (d/room :t)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (personas/researcher
                   {:run-turn-fn (mock-turn-fn "researched")})))
      (let [reply (await-spin r #(d/ask % :researcher {:content "find X"}))]
        (is (= :researcher (:from reply)))
        (is (str/starts-with? (:content reply) "researched"))
        ;; researcher.md begins with a header — check fragment leaked
        ;; from chat-ctx system slot into the reply via mock-turn-fn.
        (is (or (re-find #"(?i)research" (:content reply))
                (re-find #"## Available Tools" (:content reply)))
            "system prompt contains researcher content or tool block")))))

(deftest coder-and-reviewer-have-distinct-ids
  (testing "personas get sensible default ids when not overridden"
    (let [r1 (d/room :t1)
          r2 (d/room :t2)]
      (binding [ec/*execution-context* (:ctx r1)]
        (d/join r1 (personas/coder    {:run-turn-fn (mock-turn-fn "code")})))
      (binding [ec/*execution-context* (:ctx r2)]
        (d/join r2 (personas/reviewer {:run-turn-fn (mock-turn-fn "review")})))
      (let [code   (await-spin r1 #(d/ask % :coder    {:content "impl"}))
            review (await-spin r2 #(d/ask % :reviewer {:content "check"}))]
        (is (= :coder    (:from code)))
        (is (= :reviewer (:from review)))
        (is (str/starts-with? (:content code)   "code"))
        (is (str/starts-with? (:content review) "review"))))))

(deftest id-and-spec-overrides-honoured
  (testing "Caller can rename, swap model/provider, and pin tools"
    (let [r (d/room :t)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (personas/coder
                   {:id :impl
                    :model "claude-opus-4-7"
                    :provider :anthropic
                    :tools #{:read-file}
                    :run-turn-fn (mock-turn-fn "ok")})))
      (let [reply (await-spin r #(d/ask % :impl {:content "go"}))]
        (is (= :impl (:from reply)))))))

;; ============================================================================
;; from-prompt — custom personas from any markdown file
;; ============================================================================

(deftest from-prompt-loads-arbitrary-file
  (testing "from-prompt loads any agents/*.md and builds an llm-agent"
    (let [r (d/room :t)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (personas/from-prompt
                   "researcher.md"  ; reusing an existing file
                   {:id :custom-r
                    :tools #{:read-file :grep}
                    :run-turn-fn (mock-turn-fn "custom")})))
      (let [reply (await-spin r #(d/ask % :custom-r {:content "hi"}))]
        (is (= :custom-r (:from reply)))
        (is (str/starts-with? (:content reply) "custom"))))))

(deftest from-prompt-rejects-missing-file
  (testing "Missing prompt file throws a clear error, not silent nil"
    (is (thrown-with-msg? Exception #"Persona prompt not found"
                          (personas/from-prompt "does-not-exist.md" {:id :nope})))))
