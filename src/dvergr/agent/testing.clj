(ns dvergr.agent.testing
  "Test utilities for agent development — stub and scripted LLM functions."
  (:require [clojure.string :as str]))

(defn stub-llm
  "Create a stub LLM for testing. Returns predictable responses.

  Options:
    :responses - sequence of response maps (cycled). Each is:
                 {:content \"text\" :tool-calls [...] :stop-reason :end-turn}
    :delay-ms  - simulated latency (default 10)
    :counter   - atom to track call count (optional)

  If :responses not provided, echoes the last user message."
  [& {:keys [responses delay-ms counter]
      :or {delay-ms 10}}]
  (let [idx (atom 0)]
    (fn [messages _opts]
      (when delay-ms (Thread/sleep delay-ms))
      (when counter (swap! counter inc))
      (if responses
        (let [r (nth responses (mod @idx (count responses)))]
          (swap! idx inc)
          (merge {:content "" :tool-calls [] :stop-reason :end-turn
                  :usage {:input-tokens 10 :output-tokens 10}}
                 r))
        ;; Echo mode
        (let [last-msg (last messages)
              n (swap! idx inc)]
          {:content (str "[echo #" n "] " (subs (or (:content last-msg) "") 0
                                                (min 80 (count (or (:content last-msg) "")))))
           :tool-calls []
           :usage {:input-tokens 10 :output-tokens 10}
           :stop-reason :end-turn})))))

(defn scripted-llm
  "Create a scripted LLM for testing specific conversation flows.

  Takes a vector of {:match fn :response map} entries.
  Each turn, finds first entry where (:match entry messages) is truthy
  and returns its :response. Falls back to :default if no match.

  Example:
    (scripted-llm
      [{:match (fn [msgs] (str/includes? (:content (last msgs)) \"hello\"))
        :response {:content \"Hi there!\"}}
       {:match (fn [msgs] (some #(= \"tool\" (:role %)) msgs))
        :response {:content \"Tool executed. Done.\"}}
       {:default {:content \"I don't understand.\"}}])"
  [scripts]
  (let [default-resp (or (:default (first (filter :default scripts)))
                         {:content "[scripted: no match]"
                          :tool-calls []
                          :stop-reason :end-turn
                          :usage {:input-tokens 10 :output-tokens 10}})]
    (fn [messages _opts]
      (let [matched (first (filter (fn [s]
                                     (when-let [m (:match s)]
                                       (m messages)))
                                   scripts))]
        (merge {:content "" :tool-calls [] :stop-reason :end-turn
                :usage {:input-tokens 10 :output-tokens 10}}
               (if matched (:response matched) default-resp))))))
