(ns dvergr.test-phases
  "Kaocha hooks that print how long the run spent loading (compiling) the test
   namespaces and how long it spent running them. With the profiling plugin's
   per-test times this shows where CI's wall clock goes.")

(def ^:private started (atom nil))

(defn- secs [t0] (format "%.1f s" (/ (- (System/nanoTime) t0) 1e9)))

(defn pre-load
  "Kaocha hook: note when loading starts."
  [config]
  (reset! started {:load (System/nanoTime)})
  config)

(defn post-load
  "Kaocha hook: print the load time; note when running starts."
  [test-plan]
  (when-let [t0 (:load @started)]
    (println (str "\n[test-phases] loading test namespaces took " (secs t0))))
  (swap! started assoc :run (System/nanoTime))
  test-plan)

(defn post-run
  "Kaocha hook: print the run time."
  [result]
  (when-let [t0 (:run @started)]
    (println (str "\n[test-phases] running tests took " (secs t0))))
  result)
