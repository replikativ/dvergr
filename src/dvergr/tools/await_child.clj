(ns dvergr.tools.await-child
  ;; Tool discovery can run while the sandbox is loading. Resolve the native
  ;; observer only at execution, avoiding a discovery -> program -> sandbox cycle.
  (:require [dvergr.tools :as tools]))

(tools/register!
 {:name "await_child"
  :effect :reads
  :description "Wait for an already-started child benchmark, then return its exact settled result. Does not start work or poll the model. Pass its Job UUID; follow next-offset for more entries (up to 32, or 8 with explicitly admitted public feedback). Declared checks return booleans; unavailable-checks are unknown, not failures. Certified attempt/status and current run/status/settlement-status are separate facts. Public descriptions state the admitted rubric, not independent validation. Omit job ONLY to recover admitted child receipts after a lost start response; do not restart the benchmark or repeatedly poll. Requires the current Run's exact offer and finite deadline."
  :parameters {:type "object" :properties {:job {:type "string" :description "Job UUID from the start receipt"}
                                           :offset {:type "integer" :minimum 0 :maximum 1000000}}}
  :execute (fn [{:keys [job offset]} ctx]
             {:type :success
              :content (pr-str (if job
                                 ((requiring-resolve 'dvergr.agent.experiment-observation/await!) ctx (parse-uuid job) (or offset 0))
                                 ((requiring-resolve 'dvergr.agent.experiment-observation/children) ctx (or offset 0))))})})
