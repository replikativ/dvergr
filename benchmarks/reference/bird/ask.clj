;; Send each prompt of a prompts file (prompts.py) to a model once, as the only user
;; message: no system prompt from us, no tools, one reply. Saves the raw reply and the
;; token usage per question.
;;
;; Run from the repository root:
;;   MODEL=codex-subscription-luna clojure -J-Xmx2g -M:benchmarks \
;;     benchmarks/reference/bird/ask.clj PROMPTS.json REPLIES.json
;;
;; MODEL is a dvergr.model.registry id (default codex-subscription-luna). N=<k> sends only
;; the first k prompts (a smoke test). Four calls are in flight at a time; a failed call
;; is retried twice, 5 s apart, and recorded as {:error ...} after the third failure.
(require '[dvergr.benchmarks.live :as live] '[jsonista.core :as j] '[clojure.java.io :as io])

(let [[in out] *command-line-args*]
  (when-not (and in out)
    (binding [*out* *err*] (println "usage: ask.clj PROMPTS.json REPLIES.json"))
    (System/exit 2))
  (let [n (some-> (System/getenv "N") parse-long)
        prompts (cond->> (j/read-value (io/file in) j/keyword-keys-object-mapper) n (take n))
        gen (live/model-generate {:model (or (System/getenv "MODEL") "codex-subscription-luna")})
        ask (fn [{:keys [qid prompt]}]
              (let [t0 (System/currentTimeMillis)]
                (loop [attempt 1]
                  (let [r (try (let [resp (gen {:messages [{:role :user :content prompt}]})]
                                 {:qid qid :content (:content resp) :usage (:usage resp)
                                  :ms (- (System/currentTimeMillis) t0)})
                               (catch Throwable e {:qid qid :error (str e)}))]
                    (if (and (:error r) (< attempt 3))
                      (do (Thread/sleep 5000) (recur (inc attempt)))
                      r)))))
        results (doall (mapcat #(doall (pmap ask %)) (partition-all 4 prompts)))]
    (j/write-value (io/file out) results)
    (println "done" (count results) "errors" (count (filter :error results)))
    (shutdown-agents)
    (System/exit 0)))
