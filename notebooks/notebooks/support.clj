(ns notebooks.support
  "Tiny helpers shared by the dvergr Clay notebooks.

   The execution-context binding the algebra notebooks need is part of the
   public API now — `dvergr.discourse/with-room` (re-exported as
   `dvergr.core/with-room`). This ns just adds `settle`.")

(defn settle
  "Let the engine process posted messages before we read the log. Notebooks are
   synchronous narratives; participants reply on async spins, so we give the
   engine a beat to run them."
  ([] (settle 150))
  ([ms] (Thread/sleep ^long ms)))
