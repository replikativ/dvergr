(ns dvergr.agent.trajectory
  "Certified Attempts as trajectories: training and analysis data
   (doc/evaluation-model.md, \"export successful trajectories\").

   One trajectory per Attempt, plain data (JSON-ready): what was asked (the
   environment and its task), who answered (the candidate and its model), the
   model turns with their tool calls and results, the effects those had on the
   Attempt's world (its receipts: kind, resource, decision, idempotency, a digest
   of each result, no bodies) and the verdict (reward, checks, the verifier's
   trust tier, spend). Nothing is inferred: every field is read from the
   Attempt, its Run's persisted chat, and its effect log."
  (:require [clojure.java.io :as io]
            [jsonista.core :as j]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.program :as program]
            [dvergr.artifact :as artifact]
            [dvergr.chat.context :as chat-context]))

(defn- message [m]
  (cond-> {:role (some-> (:message/role m) name)
           :content (:message/content m)}
    (:message/tool-uses m) (assoc :tool-uses (:message/tool-uses m))
    (:message/tool-use-id m) (assoc :tool-use-id (:message/tool-use-id m))
    (:message/turn-number m) (assoc :turn (:message/turn-number m))))

(defn- effect-log [room ref]
  (when ref
    (let [store (or (some-> room :store :artifacts)
                    (some-> room :store :conn artifact/datahike-store))]
      (some-> store
              (artifact/get-value (or (parse-uuid (str ref)) ref))
              :dvergr/effect-log))))

(defn- receipt [r]
  (-> (select-keys r [:effect :resource :decision :by :idempotency :digest :ms :error])
      (update :effect #(some-> % str (subs 1)))))

(defn trajectory
  "The trajectory of certified `attempt` of `room` (its control room)."
  [room attempt]
  (let [receipt* (:attempt/receipt attempt)
        env (:attempt/environment receipt*)
        metrics (:attempt/metrics receipt*)
        run-id (:attempt/run-id attempt)
        messages (when run-id
                   (chat-context/load-messages (some-> room :store :conn) (program/run-chat-id run-id)))]
    {:attempt (str (:attempt/id attempt))
     :environment {:id (some-> (:environment/id env) str (subs 1))
                   :content-id (some-> (:environment/content-id env) str)
                   :task (:environment/task env)}
     :candidate (some-> (get-in attempt [:attempt/agent :agent/id]) str (subs 1))
     :model (:attempt/model receipt*)
     :status (some-> (:attempt/status receipt*) name)
     :reward (:attempt/reward receipt*)
     :checks (:attempt/checks receipt*)
     :verifier-trust (some-> (:verifier-trust metrics) name)
     :spend-microdollars (get-in metrics [:spend :microdollars])
     :messages (mapv message messages)
     :effects (mapv receipt (effect-log room (get-in metrics [:effects :log])))}))

(defn trajectories
  "The trajectories of `room`'s certified Attempts, newest first; `opts` as
   `episode/attempts` (:environment-id, :model, :status, :limit), and
   `:min-reward` to keep only those scoring at least that."
  [room {:keys [min-reward] :as opts}]
  (->> (episode/attempts room (dissoc opts :min-reward))
       (filter #(or (nil? min-reward)
                    (<= min-reward (or (get-in % [:attempt/receipt :attempt/reward]) 0))))
       (mapv #(trajectory room %))))

(defn write-jsonl!
  "Write `trajectories` to `path`, one JSON object per line. Returns the count."
  [path trajectories]
  (io/make-parents (io/file path))
  (with-open [w (io/writer path)]
    (doseq [t trajectories]
      (.write w ^String (j/write-value-as-string t))
      (.write w "\n")))
  (count trajectories))
