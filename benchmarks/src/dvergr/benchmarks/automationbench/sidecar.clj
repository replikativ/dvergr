(ns dvergr.benchmarks.automationbench.sidecar
  "The AutomationBench service: upstream's own Python (tools, schemas,
   rubric) behind one long-lived process speaking JSON lines
   (`resources/benchmarks/automationbench/sidecar.py`).

   It is stateless: a world goes in and comes out with every call, so the
   episode's world lives in the Run's forked world on the dvergr side. One
   process serves every Attempt; requests are serialized.

   The checkout is `AUTOMATIONBENCH_ROOT`, else
   `~/.cache/dvergr-bench/automationbench`, set up with

     git clone https://github.com/zapier/AutomationBench <root>
     git -C <root> checkout 4a8e1061254004d9dac807054eed33fad7d1ff14
     cd <root> && uv sync && uv pip install --python .venv/bin/python time-machine"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]))

(def upstream
  "The upstream revision every score here is of."
  {:repo "https://github.com/zapier/AutomationBench"
   :revision "4a8e1061254004d9dac807054eed33fad7d1ff14"})

(defn root
  "The AutomationBench checkout."
  []
  (or (System/getenv "AUTOMATIONBENCH_ROOT")
      (str (System/getProperty "user.home") "/.cache/dvergr-bench/automationbench")))

(defn available?
  "Whether the checkout's environment is set up (tests skip otherwise)."
  ([] (available? (root)))
  ([root] (.canExecute (io/file root ".venv" "bin" "python"))))

(defn- script-file
  "The sidecar script as a file: from the classpath, extracted when it is in
   a jar."
  ^File []
  (let [res (or (io/resource "benchmarks/automationbench/sidecar.py")
                (throw (ex-info "sidecar.py is not on the classpath" {:type ::no-script})))]
    (if (= "file" (.getProtocol res))
      (io/file res)
      (let [f (File/createTempFile "automationbench-sidecar" ".py")]
        (.deleteOnExit f)
        (with-open [in (io/input-stream res)] (io/copy in f))
        f))))

(def ^:private mapper (json/object-mapper {:decode-key-fn identity}))

(defn start!
  "Start a sidecar over checkout `root`."
  ([] (start! (root)))
  ([root]
   (when-not (available? root)
     (throw (ex-info (str "AutomationBench is not set up at " root " (see the namespace doc)")
                     {:type ::not-set-up :root root})))
   (let [pb (doto (ProcessBuilder. ^java.util.List [(str (io/file root ".venv" "bin" "python"))
                                                    "-u" (str (script-file))])
              (.directory (io/file root))
              (.redirectError (java.lang.ProcessBuilder$Redirect/appendTo
                               (io/file root "sidecar.log"))))
         p (.start pb)]
     {:root root
      :process p
      :in (BufferedWriter. (OutputStreamWriter. (.getOutputStream p) StandardCharsets/UTF_8))
      :out (BufferedReader. (InputStreamReader. (.getInputStream p) StandardCharsets/UTF_8))
      :lock (Object.)})))

(defn stop! [{:keys [^Process process]}]
  (when process (.destroy process)))

(defn alive? [{:keys [^Process process]}]
  (and process (.isAlive process)))

(defn request!
  "One request `{:op ...}` (keys as the sidecar names them); the result, or
   an exception carrying the sidecar's error."
  [{:keys [^BufferedWriter in ^BufferedReader out lock] :as sidecar} req]
  (locking lock
    (when-not (alive? sidecar)
      (throw (ex-info "The AutomationBench sidecar is not running" {:type ::dead})))
    (.write in ^String (json/write-value-as-string req mapper))
    (.write in "\n")
    (.flush in)
    (let [line (or (.readLine out)
                   (throw (ex-info "The AutomationBench sidecar closed its output" {:type ::dead})))
          {:strs [ok result error trace]} (json/read-value line mapper)]
      (if ok
        result
        (throw (ex-info (str "AutomationBench: " error)
                        {:type ::sidecar-error :op (:op req) :trace trace}))))))

(defonce ^:private shared (atom nil))

(defn shared!
  "The process's sidecar over `root`, started (or restarted) on demand."
  ([] (shared! (root)))
  ([root]
   (locking shared
     (let [sc @shared]
       (if (and sc (alive? sc) (= root (:root sc)))
         sc
         (do (some-> sc stop!)
             (reset! shared (start! root))))))))

(defn tasks
  "`[{\"domain\" \"id\" \"name\" \"contract\"}]` of `domains` (all when nil)."
  [sidecar domains]
  (request! sidecar (cond-> {:op "tasks"} (seq domains) (assoc :domains domains))))

(defn revision
  "The checkout's git revision."
  [sidecar]
  (get (request! sidecar {:op "hello"}) "revision"))

(defn- seed-of
  "A 31-bit seed from `parts`."
  [& parts]
  (bit-and (hash (str/join "/" parts)) 0x7fffffff))

(defn start
  "The task's prompt, tool schemas and initial world, made at `at` (epoch ms,
   nil: now) with the task's seed."
  [sidecar domain id {:keys [toolset at]}]
  (request! sidecar (cond-> {:op "start" :domain domain :id id :toolset (or toolset "api")
                             :seed (seed-of domain id)}
                      at (assoc :at at))))

(defn call
  "Call `n` (0-based) of an episode: `{\"world\" \"digest\" \"content\" \"error\" \"at\"}`."
  [sidecar domain id {:keys [toolset world n name arguments at]}]
  (request! sidecar (cond-> {:op "call" :domain domain :id id :toolset (or toolset "api")
                             :world world :name name :arguments (or arguments {})
                             :seed (seed-of domain id n)}
                      at (assoc :at at))))

(defn replay
  "The episode `calls` (`[{:name :arguments :n :at}]`) in one process on one
   world, from the initial world made at `start-at`."
  [sidecar domain id {:keys [toolset start-at calls]}]
  (request! sidecar {:op "replay" :domain domain :id id :toolset (or toolset "api")
                     :start {:at start-at :seed (seed-of domain id)}
                     :calls (mapv (fn [{:keys [name arguments n at]}]
                                    {:name name :arguments (or arguments {}) :at at
                                     :seed (seed-of domain id n)})
                                  calls)}))

(defn grade
  "Upstream's rubric on `world`: `{\"partial_credit\" \"passed\" \"assertions\"}`."
  [sidecar domain id world]
  (request! sidecar {:op "grade" :domain domain :id id :world world}))
