(ns dvergr.cli.main
  "CLI entry point — starts the daemon, an nREPL server, and (if
   available on the classpath) the TUI.

   The daemon is the system; nREPL, TUI, and the web dashboard are
   interchangeable FRONTENDS over it — turn each on per-process.

   Usage:
     clj -M:cli                       ; daemon + nREPL on :7888 + TUI
     clj -M:cli -p 18080              ; pick the nREPL port
     clj -M:cli --no-tui              ; headless: daemon + nREPL only
     clj -M:cli --web                 ; web dashboard on 127.0.0.1:17880 (loopback)
     clj -M:cli --no-tui --web        ; server box: daemon + nREPL + web, no TUI
     clj -M:cli --web --web-port 9090 ; web on a custom port
     clj -M:cli --help

   The nREPL server is the integration seam for Claude Code, Cursor,
   Calva, or any nREPL client. From there agents can be inspected
   and driven via `dvergr.actors`, `dvergr.orchestration.skills`, `dvergr.rooms`,
   `dvergr.discourse.commands`, etc. The TUI is the terminal chat-and-overview
   surface; the web dashboard is the same rooms model in a browser.

   When `--no-tui` is passed (or the TUI namespaces aren't on the
   classpath), the process stays up on the daemon + nREPL (+ web if
   requested) alone."
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.log :as dvergr-log]
            [nrepl.server :as nrepl])
  ;; AOT-compiled to a real Java entry class so the harness uberjar runs as
  ;; `java -jar dvergr-…-harness.jar` (see build.clj `harness`).
  (:gen-class))

(def ^:private cli-options
  [["-p" "--port PORT" "nREPL port"
    :default  7888
    :parse-fn #(Integer/parseInt %)]
   [nil  "--no-tui"     "Run without the TUI (daemon + nREPL only)"]
   [nil  "--web"        "Also start the web dashboard"]
   [nil  "--web-port PORT" "Web dashboard port (with --web)"
    :default  17880
    :parse-fn #(Integer/parseInt %)]
   [nil  "--web-bind IP" "Web dashboard bind address (with --web); the UI/API are unauthenticated, so default is loopback"
    :default  "127.0.0.1"]
   ["-h" "--help"       "Show this help"]])

(defn- resolve-web-start
  "Resolve dvergr.web.server/start! — it ships in the library now, but loading it
   pulls reitit (the web's only non-core dep), so this is nil unless reitit is on
   the classpath (the :cli alias provides it)."
  []
  (try (requiring-resolve 'dvergr.web.server/start!)
       (catch Throwable _ nil)))

(defn- resolve-web-stop []
  (try (requiring-resolve 'dvergr.web.server/stop!)
       (catch Throwable _ nil)))

(defn- resolve-tui-run
  "Return the TUI launch fn `(fn [daemon])` if the TUI namespaces are
   on the classpath, else nil. Lets `dvergr.cli.main` be loadable
   from a library context that doesn't pull in spindel-tui."
  []
  (try
    (require 'dvergr.tui.app)
    (some-> (ns-resolve 'dvergr.tui.app 'run) deref)
    (catch Throwable _ nil)))

(defn- write-nrepl-port-file!
  "Write the nREPL port to `.nrepl-port` in the current working
   directory so editors (CIDER, Calva, Cursive) and `clj-nrepl-eval`
   can auto-discover the connection without flags. Deletes on exit."
  [port]
  (let [f (io/file ".nrepl-port")]
    (try
      (spit f (str port))
      (.deleteOnExit f)
      (catch Throwable _))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (seq errors)
      (binding [*out* *err*]
        (run! println errors)
        (println)
        (println summary)
        (System/exit 1))

      (:help options)
      (do (println summary) (System/exit 0))

      :else
      (let [{:keys [port no-tui web web-port web-bind]} options
            tui-run        (when-not no-tui (resolve-tui-run))
            web-start      (when web (resolve-web-start))
            web-stop       (resolve-web-stop)
            nrepl-server   (nrepl/start-server :port port)
            _              (write-nrepl-port-file! port)
            d-ref          (atom nil)
            ;; The REAL console PrintStream, captured BEFORE `dvergr-log/init!`
            ;; redirects System.out/err + *out*/*err* to the log file — so a boot
            ;; failure can still be surfaced to the terminal.
            console        System/out
            stop-web!      (fn [] (when web-stop (try (web-stop) (catch Throwable _))))
            shutdown-hook  (Thread.
                            (fn []
                              (stop-web!)
                              (when-let [d @d-ref]
                                (try (daemon/stop! d) (catch Throwable _)))
                              (try (nrepl/stop-server nrepl-server)
                                   (catch Throwable _))))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
        (println (str "[dvergr] nREPL listening on port " port))
        (println (str "[dvergr] Logging to "             (dvergr-log/default-log-file)))
        (when (and (not no-tui) (not tui-run))
          (println "[dvergr] TUI not on classpath — running headless. "
                   "Use the :cli alias (which provides spindel-tui) to enable."))
        (when (and web (not web-start))
          (println "[dvergr] --web given but the web server couldn't load (reitit missing). "
                   "Use the :cli alias (which provides reitit) to enable."))
        (flush)
        (try
          (dvergr-log/init!)                 ; from here, console output goes to the log
          (let [d (try
                    (daemon/start-from-config!)
                    (catch Throwable t
                      ;; Boot failed. `init!` has already redirected the console to
                      ;; the log, so the error would otherwise vanish and the
                      ;; process would linger as a bare nREPL (no TUI, no message).
                      ;; Surface it on the captured real console, tear down, EXIT.
                      (.println console (str "\n[dvergr] FAILED to start — " (.getMessage t)))
                      (.println console (str "[dvergr] full stack trace in the log: "
                                             (dvergr-log/default-log-file)))
                      (.flush console)
                      (try (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
                           (catch Throwable _))
                      (try (nrepl/stop-server nrepl-server) (catch Throwable _))
                      (System/exit 1)))]
            (reset! d-ref d)
            (when web-start
              (try (web-start d :port web-port :ip web-bind)
                   (catch Throwable t
                     (.println console (str "[dvergr] web dashboard failed to start: "
                                            (.getMessage t))))))
            (try
              (if tui-run
                (tui-run d)
                ;; Headless: block forever so the daemon stays up.
                @(promise))
              (finally
                (stop-web!)
                (daemon/stop! d)
                (reset! d-ref nil))))
          (finally
            (try (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
                 (catch Throwable _))
            (try (nrepl/stop-server nrepl-server) (catch Throwable _))))))))
