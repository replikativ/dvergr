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
  "`require` under Clojure's global require lock."
  [& args]
  (locking clojure.lang.RT/REQUIRE_LOCK
    (apply require args)))
