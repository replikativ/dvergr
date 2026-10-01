(ns dvergr.substrate.load
  "Thread-safe runtime namespace loading.

   `clojure.core/require` is not safe to call concurrently: two threads
   loading the same namespace for the first time can each see it half
   defined (unbound vars, spurious compile errors in its dependencies), and a
   failed load stays broken for the process. Working contexts load sandbox
   capability namespaces lazily, and parallel callers (benchmark episodes,
   probes, forked agents) create their first contexts at the same moment.
   `require!` takes the same global lock as `requiring-resolve`.")

(defn require!
  "`require` under Clojure's global require lock, which only a load needs:
   when every lib is already loaded (and nothing asks to reload), `require`
   loads nothing and runs without it. Contexts call this on every creation;
   under the lock, parallel benchmark cells queued on it (28 s of 64 cells'
   lock waits)."
  [& args]
  (let [libs (keep #(cond (symbol? %) % (vector? %) (first %)) args)
        loaded (loaded-libs)]
    (if (and (seq libs)
             (not-any? #{:reload :reload-all} args)
             (every? #(contains? loaded %) libs))
      (apply require args)
      (locking clojure.lang.RT/REQUIRE_LOCK
        (apply require args)))))
