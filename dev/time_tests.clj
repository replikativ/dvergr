(ns time-tests
  "Per-ns timing instrumentation. Loads each test ns, runs its tests,
   prints the wallclock + tests/assertions/failures/errors. Single JVM
   so cold-start cost is amortized — what shows up here is real per-ns work."
  (:require [clojure.test :as t]
            [clojure.string :as str]))

(def ^:dynamic *integration?* (= "1" (System/getenv "INTEGRATION")))

(defn- test-namespaces []
  (->> (file-seq (java.io.File. "test"))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (subs (count "test/"))
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace \/ \.)
                 symbol))
       sort))

(defn- run-one [ns-sym]
  (let [t0 (System/nanoTime)]
    (try
      (require ns-sym :reload)
      (let [load-ms (/ (- (System/nanoTime) t0) 1e6)
            t1 (System/nanoTime)
            r (binding [t/*test-out* (java.io.StringWriter.)]
                (t/run-tests ns-sym))
            run-ms (/ (- (System/nanoTime) t1) 1e6)]
        (printf "%-50s  load=%7.0fms run=%7.0fms tests=%3d asserts=%3d fail=%d err=%d%n"
                (str ns-sym) load-ms run-ms
                (:test r 0) (:pass r 0) (:fail r 0) (:error r 0))
        (flush)
        {:ns ns-sym :load-ms load-ms :run-ms run-ms
         :tests (:test r 0) :pass (:pass r 0) :fail (:fail r 0) :error (:error r 0)})
      (catch Throwable ex
        (let [total-ms (/ (- (System/nanoTime) t0) 1e6)]
          (printf "%-50s  FAILED after %.0fms: %s%n"
                  (str ns-sym) total-ms (.getMessage ex))
          (flush)
          {:ns ns-sym :load-ms total-ms :error 1})))))

(defn -main [& _]
  (let [nses (test-namespaces)
        results (mapv run-one nses)
        total-load (reduce + (keep :load-ms results))
        total-run  (reduce + (keep :run-ms  results))]
    (println)
    (println "==== Summary ====")
    (printf "Total: load=%.1fs run=%.1fs wall=%.1fs across %d ns%n"
            (/ total-load 1000.0) (/ total-run 1000.0)
            (/ (+ total-load total-run) 1000.0) (count results))
    (println)
    (println "Top 10 by load-ms:")
    (doseq [r (->> results (sort-by :load-ms >) (take 10))]
      (printf "  %-50s  %7.0fms%n" (str (:ns r)) (or (:load-ms r) 0)))
    (println)
    (println "Top 10 by run-ms:")
    (doseq [r (->> results (sort-by :run-ms >) (take 10))]
      (printf "  %-50s  %7.0fms%n" (str (:ns r)) (or (:run-ms r) 0)))
    (System/exit 0)))
