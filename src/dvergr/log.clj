(ns dvergr.log
  "Structured logging via Telemere, with Timbre redirect for datahike etc.

   Call (init!) once at startup. All other namespaces use telemere directly:

     (require '[taoensso.telemere :as tel])
     (tel/log! {:id :daemon/start :data {:agents 3}} \"Starting\")

   Timbre calls (datahike, other libs) are redirected into Telemere so
   everything ends up in the same file.

   Default log file: /tmp/dvergr.log"
  (:require [taoensso.telemere :as tel]
            [taoensso.telemere.timbre :as tel-timbre]
            [taoensso.timbre :as timbre]))

(def default-log-file "/tmp/dvergr.log")

(defn init!
  "Configure logging. Call once at program entry before starting the daemon.
   - Redirects System.err to the log file (captures JVM module warnings, JUL)
   - Removes JUL ConsoleHandlers (java.util.logging — Lucene, etc.)
   - Telemere: disables console handler, adds file handler
   - Timbre: removes println appender, redirects all calls into Telemere"
  ([] (init! default-log-file))
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

   ;; Remove all JUL (java.util.logging) ConsoleHandlers from the root logger.
   ;; Lucene uses JUL and would otherwise write to the terminal.
   (let [root (java.util.logging.Logger/getLogger "")]
     (doseq [h (into [] (.getHandlers root))]
       (.removeHandler root h)))

   ;; Telemere: silence console, write to file
   (tel/remove-handler! :default/console)
   (tel/add-handler! :file/main
     (tel/handler:file
       {:path     path
        :interval nil}))

   ;; Timbre (datahike, other libs): redirect into Telemere
   ;; Set min-level :info to drop the noisy DEBUG transact messages
   ;; (Timbre 4.10 calls it set-level!; v5 renamed to set-min-level!)
   (timbre/set-level! :info)
   (timbre/merge-config!
     {:appenders
      {:println  {:enabled? false}
       :telemere (tel-timbre/timbre->telemere-appender)}})))
