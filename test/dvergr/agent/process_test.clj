(ns dvergr.agent.process-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.process :as process]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-context]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(deftest process-worker-does-not-inherit-the-callers-drain-marker
  (let [execution-ctx (context/create-execution-context)
        chat-ctx (turn/new-working-ctx
                  {:execution-ctx execution-ctx
                   :title "process drain-boundary"
                   :durable? false})
        completed (promise)
        caller-spin-id (random-uuid)]
    (try
      (binding [ec/*execution-context* execution-ctx
                ;; A real tool call is initiated by an engine drain slice.
                simple/*in-drain?* true
                ec/*spin-id* caller-spin-id]
        (process/->process
         chat-ctx
         {:description "deref a Spin on the process worker"
          :on-complete #(deliver completed {:value %})
          :on-abort #(deliver completed {:error %})}
         #(hash-map :inherited-spin-id ec/*spin-id*
                    :result (deref (sp/spin :completed)))))
      (is (= {:value {:inherited-spin-id nil :result :completed}}
             (deref completed 5000 ::timeout)))
      (finally
        (chat-context/close-chat! chat-ctx)
        (context/close-context! execution-ctx)))))
