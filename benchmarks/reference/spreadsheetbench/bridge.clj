;; LLM bridge for driver.py (SpreadsheetBench's reference harness): JSON lines on stdin
;;   {"id": .., "messages": [{"role": "user"|"assistant", "content": ..}]}
;; -> "@@RESP {json}" lines on stdout ({"id" .., "content" .., "usage" .., "ms" ..} or {"id" .. "error" ..}).
;; No system prompt, no tools: upstream's llm_api.py sends only the alternating messages.
;; At most 4 calls in flight; a failed call is retried twice, 5 s apart.
;;
;; driver.py starts it from the repository root as
;;   clojure -J-Xmx2g -M:benchmarks benchmarks/reference/spreadsheetbench/bridge.clj
;; with MODEL (a dvergr.model.registry id, default codex-subscription-luna) in the environment.
(require '[dvergr.benchmarks.live :as live] '[jsonista.core :as j])
(import '[java.util.concurrent Executors])
(def gen (live/model-generate {:model (or (System/getenv "MODEL") "codex-subscription-luna")}))
(def pool (Executors/newFixedThreadPool 4))
(def out-lock (Object.))
(defn emit [m] (locking out-lock (.println System/out (str "@@RESP " (j/write-value-as-string m))) (.flush System/out)))
(defn ask [{:keys [id messages]}]
  (let [t0 (System/currentTimeMillis)
        msgs (mapv (fn [{:keys [role content]}] {:role (keyword role) :content content}) messages)]
    (loop [attempt 1]
      (let [r (try (let [resp (gen {:messages msgs})]
                     {:id id :content (:content resp) :usage (:usage resp) :attempts attempt
                      :ms (- (System/currentTimeMillis) t0)})
                   (catch Throwable e {:id id :error (str e) :attempts attempt}))]
        (if (and (:error r) (< attempt 3)) (do (Thread/sleep 5000) (recur (inc attempt))) r)))))
(emit {:id "ready"})
(loop []
  (when-let [line (read-line)]
    (let [req (j/read-value line j/keyword-keys-object-mapper)]
      (.submit pool ^Runnable (fn [] (emit (try (ask req) (catch Throwable e {:id (:id req) :error (str e)}))))))
    (recur)))
(.shutdown pool)
(.awaitTermination pool 1 java.util.concurrent.TimeUnit/HOURS)
(shutdown-agents)
(System/exit 0)
