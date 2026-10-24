(ns dvergr.chat.outcome
  "Closed controlled-stop outcome shared by turn drivers. No execution state.")

(def repeated-tool-call {:status :stopped :reason :repeated-tool-call})
(def stop-diagnostic "Stopped after repeated identical tool calls produced no progress.")

(defn stopped? [result]
  (= repeated-tool-call result))

(defn as-error [result]
  (when (stopped? result)
    (ex-info stop-diagnostic {:type ::stopped :outcome result})))

(defn error-stop-reason [error]
  (when (= {:type ::stopped :outcome repeated-tool-call} (ex-data error))
    :repeated-tool-call))
