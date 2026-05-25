(ns dvergr.main
  "Entry point for the dvergr daemon with TUI dashboard.

   Starts nREPL, the daemon (agents + channels), and the TUI.

   Usage:  clj -M:dashboard"
  (:require [dvergr.daemon :as daemon]
            [dvergr.log :as dvergr-log]
            [dvergr.tui.dashboard :as dashboard]
            [nrepl.server :as nrepl]))

(defn- start-nrepl!
  "Start an nREPL server on the given port. Returns the server."
  [port]
  (let [server (nrepl/start-server :port port)]
    (println (str "[main] nREPL server started on port " port))
    server))

(defn -main [& args]
  (let [nrepl-port (if (seq args)
                     (Integer/parseInt (first args))
                     7888)]

    ;; 1. Start nREPL — announce port before log file takes over
    (let [nrepl-server (start-nrepl! nrepl-port)
          d-ref        (atom nil)
          ;; Shutdown hook covers SIGTERM / hard kill / IDE stop — the
          ;; try/finally below only runs on a clean :quit from the TUI.
          ;; Without this, a hard exit leaves the Lucene write.lock on
          ;; disk and the next `clj -M:dashboard` fails at start.
          shutdown-hook
          (Thread.
            (fn []
              (when-let [d @d-ref]
                (try (daemon/stop! d) (catch Throwable _)))
              (try (nrepl/stop-server nrepl-server) (catch Throwable _))))]
      (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
      (println (str "[main] Logging to " dvergr-log/default-log-file
                    "  (tail -f " dvergr-log/default-log-file ")"))
      (flush)
      (try
        ;; 2. Init structured logging to file (disables console output)
        (dvergr-log/init!)

        ;; 3. Start daemon from config.local.edn
        (let [d (daemon/start-from-config!)]
          (reset! d-ref d)
          (try
            ;; 4. Start TUI dashboard (blocks main thread)
            (dashboard/run d)
            (finally
              (daemon/stop! d)
              (reset! d-ref nil))))
        (finally
          (try (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
               (catch Throwable _))
          (nrepl/stop-server nrepl-server))))))
