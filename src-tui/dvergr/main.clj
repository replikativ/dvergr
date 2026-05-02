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
    (let [nrepl-server (start-nrepl! nrepl-port)]
      (println (str "[main] Logging to " dvergr-log/default-log-file
                    "  (tail -f " dvergr-log/default-log-file ")"))
      (flush)
      (try
        ;; 2. Init structured logging to file (disables console output)
        (dvergr-log/init!)

        ;; 3. Start daemon from config.local.edn
        (let [d (daemon/start-from-config!)]
          (try
            ;; 4. Start TUI dashboard (blocks main thread)
            (dashboard/run d)
            (finally
              (daemon/stop! d))))
        (finally
          (nrepl/stop-server nrepl-server))))))
