(ns dvergr.tools.await-child
  (:require [dvergr.agent.experiment-observation :as observation]
            [dvergr.tools :as tools]))

(tools/register!
 {:name "await_child"
  :effect :reads
  :description "Wait for an already-started child benchmark, then return its exact settled result. Does not start work or poll the model. Pass its Job UUID; optional offset pages 32 result entries or 128 child receipts. Omit job ONLY to recover admitted child receipts after a lost start response; do not restart the benchmark or repeatedly poll. Requires the current Run's exact offer and finite deadline."
  :parameters {:type "object" :properties {:job {:type "string" :description "Job UUID from the start receipt"}
                                           :offset {:type "integer" :minimum 0 :maximum 1000000}}}
  :execute (fn [{:keys [job offset]} ctx]
             {:type :success
              :content (pr-str (if job
                                 (observation/await! ctx (parse-uuid job) (or offset 0))
                                 (observation/children ctx (or offset 0))))})})
