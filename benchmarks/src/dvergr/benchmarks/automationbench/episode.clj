(ns dvergr.benchmarks.automationbench.episode
  "An AutomationBench episode in a Run's world: the task's initial world,
   prompt and tool schemas (from the world setup) and the current world live
   in the world's state; each tool call goes through the service
   (`call-tool!`) and is recorded as the verifier replays it. Upstream's own
   loop is `run-episode!`; Dvergr's is `harness`."
  (:require [dvergr.benchmarks.automationbench.sidecar :as sc]
            [dvergr.benchmarks.pyjson :as pj]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private episode-path [::episode])

(defn episode-state [room]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/get-state episode-path)))

(defn swap-episode! [room f & args]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/swap-state! episode-path #(apply f % args))))

(defn- add-usage [acc usage]
  (if (map? usage)
    (merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                acc (select-keys usage [:input-tokens :output-tokens
                                        :cache-read-tokens :cache-creation-tokens]))
    acc))

(defn- ->message [{:strs [role content]}]
  {:role (keyword role) :content content})

(defn call-tool!
  "Call `name` with `arguments` through the service on `room`'s world; the
   new world replaces it and the call is appended to `calls` (an atom) as the
   verifier replays it. Returns upstream's text result."
  [{:keys [room sidecar task calls]} name arguments]
  (let [{:keys [domain id toolset]} task
        n (count @calls)
        args (pj/stringify-keys (dissoc (or arguments {}) :db/id))
        r (sc/call sidecar domain id {:toolset toolset :n n :name name :arguments args
                                      :world (:world (episode-state room))})]
    (swap-episode! room assoc :world (get r "world") :digest (get r "digest"))
    (swap! calls conj {:n n :name name :arguments args :at (get r "at")
                       :error (boolean (get r "error"))})
    (get r "content")))

(defn task-prompt
  "The task's `{:system :messages :tools}` from the world setup."
  [room]
  (let [{:keys [prompt tools]} (episode-state room)]
    {:system (some #(when (= "system" (get % "role")) (get % "content")) prompt)
     :messages (into [] (comp (remove #(= "system" (get % "role"))) (map ->message)) prompt)
     :tools tools}))

(defn run-episode!
  "Upstream's rollout in `room`'s world: model steps (`generate`, taking
   `{:system :messages :tools}`) until a reply without tool calls or
   `max-turns` model steps. Returns `{:termination :calls :transcript
   :usage :model-steps}`; every call's world is in the Run's world as it
   happens."
  [{:keys [room generate max-turns cancelled?] :as episode}]
  (let [{:keys [system messages tools]} (task-prompt room)
        opening messages
        calls (atom [])
        episode (assoc episode :calls calls)]
    (loop [turn 0 history opening usage {}]
      (let [done (fn [termination turns history usage]
                   {:termination termination :calls @calls :usage usage
                    :model-steps turns :transcript (subvec history (count opening))})]
        (cond
          (and cancelled? (cancelled?)) (done :cancelled turn history usage)
          (>= turn max-turns) (done :max-turns turn history usage)
          :else
          (let [{:keys [content tool-calls] :as response}
                (generate {:system system :messages history :tools tools})
                usage (add-usage usage (:usage response))
                history (conj history (cond-> {:role :assistant :content content}
                                        (seq tool-calls) (assoc :tool-calls (vec tool-calls))))]
            (if (empty? tool-calls)
              (done :agent-stop (inc turn) history usage)
              (recur (inc turn)
                     (into history (map (fn [{call-id :id :keys [name arguments]}]
                                          {:role :tool :id call-id
                                           :content (call-tool! episode name arguments)}))
                           tool-calls)
                     usage))))))))
