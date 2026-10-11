(ns dvergr.substrate.log
  "Structured logging via Telemere, with Timbre redirect for datahike etc.

   Call (init!) once at startup. All other namespaces use telemere directly:

     (require '[taoensso.telemere :as tel])
     (tel/log! {:id :daemon/start :data {:agents 3}} \"Starting\")

   Timbre calls (datahike, other libs) are redirected into Telemere so
   everything ends up in the same file.

   Default log file: .dvergr/dvergr.log (see dvergr.substrate.paths)"
  (:require [taoensso.telemere :as tel]
            [taoensso.telemere.timbre :as tel-timbre]
            [taoensso.timbre :as timbre]
            [dvergr.substrate.paths :as paths]))

(defn default-log-file
  "The default log path: `.dvergr/dvergr.log` under the project state root."
  []
  (paths/log-file))

(declare to-file!)

(defn- jul->telemere
  "A java.util.logging handler that hands each record (INFO and up) to
   Telemere."
  ^java.util.logging.Handler []
  (let [fmt (java.util.logging.SimpleFormatter.)]
    (doto (proxy [java.util.logging.Handler] []
            (publish [^java.util.logging.LogRecord r]
              (when (.isLoggable ^java.util.logging.Handler this r)
                (let [v (.intValue (.getLevel r))
                      level (cond (>= v 1000) :error (>= v 900) :warn :else :info)]
                  (tel/log! {:level level :id :jul/record
                             :data {:logger (.getLoggerName r)}
                             :error (.getThrown r)}
                            (.formatMessage fmt r)))))
            (flush [])
            (close []))
      (.setLevel java.util.logging.Level/INFO))))

(defn init!
  "Configure logging. Call once at program entry before starting the daemon.
   - Redirects System.err to the log file (captures JVM module warnings, JUL)
   - Removes JUL ConsoleHandlers (java.util.logging — Lucene, etc.)
   - Telemere: disables console handler, adds file handler
   - Timbre: removes println appender, redirects all calls into Telemere"
  ([] (init! (default-log-file)))
  ([path]
   ;; Redirect System.out, System.err, and Clojure's *out*/*err* to the log file.
   ;; - System.err captures JVM module-system warnings (java.lang.foreign.Linker etc.)
   ;; - System.out + *out* capture Telemere's :default/console handler output (Trove libs)
   ;; Note: JLine (spindel-tui) uses the raw TTY fd directly, NOT System.out, so
   ;; redirecting System.out here does NOT break the TUI terminal rendering.
   (let [fos (java.io.FileOutputStream. (java.io.File. ^String path) true)
         ps  (java.io.PrintStream. fos true "UTF-8")
         pw  (java.io.PrintWriter. (java.io.OutputStreamWriter. fos "UTF-8") true)]
     (System/setOut ps)
     (System/setErr ps)
     (alter-var-root #'*out* (constantly pw))
     (alter-var-root #'*err* (constantly pw)))

   (to-file! path)))

(defn to-file!
  "Send the log records (Telemere, Trove, Timbre, java.util.logging) to the file at
   `path` instead of the console, leaving stdout and stderr alone: a command
   line tool keeps stdout for its own output (`dvergr.catalog.room-run`)."
  [path]
  (some-> (java.io.File. ^String (str path)) .getAbsoluteFile .getParentFile .mkdirs)
  ;; JUL (java.util.logging; Lucene uses it): its console handlers go, and
  ;; its records reach Telemere, so the same file
  (let [root (java.util.logging.Logger/getLogger "")]
    (doseq [h (into [] (.getHandlers root))]
      (.removeHandler root h))
    (.addHandler root (jul->telemere)))

   ;; Telemere: silence console, write to file
  (tel/remove-handler! :default/console)
  (tel/add-handler! :file/main
                    (tel/handler:file
                     {:path     (str path)
                      :interval nil}))

   ;; Trove (datahike's warnings, e.g. :datahike/value-caps-unset): into
   ;; Telemere, else its console backend prints them to stdout
  (try
    (let [log-fn ((requiring-resolve 'taoensso.trove.telemere/get-log-fn))]
      (alter-var-root (requiring-resolve 'taoensso.trove/*log-fn*) (constantly log-fn)))
    (catch Throwable _ nil))

   ;; Timbre (datahike, other libs): redirect into Telemere
   ;; Set min-level :info to drop the noisy DEBUG transact messages
   ;; (Timbre 4.10 calls it set-level!; v5 renamed to set-min-level!)
  (timbre/set-level! :info)
  (timbre/merge-config!
   {:appenders
    {:println  {:enabled? false}
     :telemere (tel-timbre/timbre->telemere-appender)}}))
