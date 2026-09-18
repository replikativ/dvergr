(ns dvergr.benchmarks.tau2.inspect
  "Read-only inspection of a recorded tau2 experiment store.

     (def xs (conv/open-store! \".dvergr/benchmarks/<experiment>\"))
     (summary xs :tau2/<experiment>)               ; per candidate: reward, pass^k
     (attempts xs :tau2/<experiment>)              ; certified Attempts
     (def e (episode xs :tau2/<experiment> attempt-id))
     (print-transcript e)                          ; dialogue + tool effects + agent code
     (verify-world dom task e)                     ; replay effects -> recorded world hash

   Everything is read from the durable store: Attempts and the episode Run
   from the experiment Room, and dialogue, candidate `:agent-turn` Runs,
   `:_activity` rows (the candidate's tool uses, e.g. `clojure_eval` code)
   and environment effect rows from each episode Room."
  (:require [dvergr.agent.conversation :as conv]
            [dvergr.benchmarks.tau2.runner :as runner]
            [dvergr.room.store :as store]))

(defn attempts
  "Certified Attempts of the experiment Room, oldest first."
  [{:keys [store]} experiment-room-id]
  (->> (store/-list-attempts store experiment-room-id {:limit 100000})
       (sort-by #(get-in % [:attempt/receipt :attempt/started-at]))
       vec))

(defn scorecards [{:keys [store]} experiment-room-id]
  (store/-list-scorecards store experiment-room-id {:limit 1000}))

(defn attempt-row
  "One flat row per Attempt."
  [a]
  (let [r (:attempt/receipt a)]
    {:attempt/id (:attempt/id a)
     :candidate (get-in a [:attempt/agent :agent/id])
     :environment (get-in a [:attempt/environment :environment/id])
     :task (get-in a [:attempt/environment :environment/task :task-id])
     :status (:attempt/status r)
     :reward (:attempt/reward r)
     :checks (:attempt/checks r)
     :termination (get-in r [:attempt/metrics :termination])
     :steps (get-in r [:attempt/metrics :steps])
     :elapsed-s (quot (:attempt/elapsed-ms r) 1000)
     :episode-room (get-in a [:attempt/evidence :episode :room])}))

(defn current-attempts
  "Drop :failed Attempts superseded by a completed Attempt of the same cell
   (a resumed experiment re-runs its failed cells)."
  [attempts]
  (let [cell (fn [a] [(get-in a [:attempt/receipt :attempt/metrics :experiment-content-id])
                      (:attempt/agent-def-hash a)
                      (get-in a [:attempt/environment :environment/content-id])
                      (get-in a [:attempt/receipt :attempt/metrics :repetition])])
        completed (set (map cell (filter #(= :completed (get-in % [:attempt/receipt :attempt/status]))
                                         attempts)))]
    (vec (remove #(and (not= :completed (get-in % [:attempt/receipt :attempt/status]))
                       (completed (cell %)))
                 attempts))))

(defn summary
  "Per candidate: scored attempts, mean reward, pass^k over repetitions, and
   infrastructure failures (status :failed, excluded from metrics)."
  [xs experiment-room-id]
  (->> (attempts xs experiment-room-id)
       (current-attempts)
       (map attempt-row)
       (group-by :candidate)
       (map (fn [[candidate rows]]
              (let [scored (filter #(= :completed (:status %)) rows)
                    episodes (map (fn [r] {:task-id (:task r) :termination (:termination r)
                                           :grade {:reward (:reward r)}})
                                  scored)]
                (merge (runner/metrics episodes)
                       {:candidate candidate
                        :attempts (count rows)
                        :failed (count (remove #(= :completed (:status %)) rows))}))))
       (sort-by (comp str :candidate))
       vec))

(defn episode
  "Reconstruct one episode from the store."
  [{:keys [store]} experiment-room-id attempt-id]
  (let [a (store/-load-attempt store experiment-room-id attempt-id)
        room-id (get-in a [:attempt/evidence :episode :room])
        messages (conv/room-messages store room-id)]
    {:attempt a
     :episode-run (store/-load-run store experiment-room-id attempt-id)
     :room room-id
     :runs (store/-list-runs store room-id {:limit 100000})
     ;; Dialogue: messages between the two participants, plus the
     ;; customer's final (stop / after-bound) rows kept off the agent's inbox.
     :dialogue (vec (filter #(and (#{:agent :customer} (:from %))
                                  (not (conv/effect-row? %))
                                  (or (not= :_activity (:to %))
                                      (= :customer (:from %))))
                            messages))
     :activities (vec (filter #(and (= :agent (:from %)) (= :_activity (:to %))) messages))
     :effects (conv/effects store room-id)}))

(defn print-transcript
  "Human-readable episode: dialogue, the candidate's tool uses (including REPL
   code) and every environment effect, in recorded order."
  ([e] (print-transcript e {:max-chars 400}))
  ([{:keys [attempt dialogue activities effects]} {:keys [max-chars]}]
   (let [clip #(let [s (str %)] (if (> (count s) max-chars) (str (subs s 0 max-chars) " …") s))
         r (:attempt/receipt attempt)]
     (println "Attempt" (:attempt/id attempt) "reward" (:attempt/reward r)
              "checks" (:attempt/checks r) "termination" (get-in r [:attempt/metrics :termination]))
     (doseq [m (sort-by (juxt #(or (:ts %) 0) #(str (:id %)))
                        (concat (map #(assoc % ::k :dialogue) dialogue)
                                (map #(assoc % ::k :activity) activities)))]
       (case (::k m)
         :dialogue (println (str "\n[" (name (:from m)) " → " (name (:to m)) "] " (clip (:content m))))
         :activity (doseq [tu (get-in m [:metadata :tool-uses] (:tool-uses m))]
                     (println (str "  ⚙ " (or (:tool-use/name tu) (:name tu)) " "
                                   (clip (pr-str (dissoc (or (:tool-use/input tu) (:input tu))
                                                         :db/id))))))))
     (println "\nEnvironment effects:")
     (doseq [{:keys [seq requestor tool arguments content error]} effects]
       (println (str "  #" seq " " (name requestor) " " tool " " (pr-str arguments)
                     (when error " ERROR") "\n     → " (clip content)))))))

(defn verify-world
  "Replay the recorded effects on the task's initial world and compare with
   the certified final world hash. Returns `{:replayed-hash :recorded-hash :match?}`."
  [domain task {:keys [attempt effects]}]
  (let [w (reduce (fn [w {:keys [requestor tool arguments]}]
                    (:world ((:respond domain) w requestor tool arguments)))
                  ((:initial-world domain) task)
                  effects)
        replayed ((:world-hash domain) w)
        recorded (get-in attempt [:attempt/evidence :world :final-hash])]
    {:replayed-hash replayed :recorded-hash recorded :match? (= replayed recorded)}))

(defn verify-episode
  "Cross-check a certified episode against the durable Room rows: the graded
   trajectory's tool entries equal the effect rows, its dialogue equals the
   Room dialogue, and every recorded candidate Run exists and is terminal."
  [{:keys [attempt dialogue effects runs]}]
  (let [log (get-in attempt [:attempt/evidence :trajectory])
        logged-tools (mapv #(select-keys % [:seq :requestor :tool :arguments :content :error])
                           (filter #(= :tool (:kind %)) log))
        logged-dialogue (mapv :content (filter #(= :message (:kind %)) log))
        recorded-runs (set (map first (get-in attempt [:attempt/evidence :episode :agent-runs])))]
    {:effects-match? (= logged-tools (mapv #(select-keys % [:seq :requestor :tool :arguments :content :error])
                                           effects))
     :dialogue-match? (= logged-dialogue (mapv :content dialogue))
     :runs-recorded? (= recorded-runs (set (map :run/id runs)))
     :runs-terminal? (every? #(not= :running (:run/status %)) runs)}))
