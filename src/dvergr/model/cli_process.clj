(ns dvergr.model.cli-process
  "Ownership for a trusted inference CLI, not an untrusted native-code sandbox.
   Explicit text calls use a private Linux session so an exited parent cannot
   hide descendants retaining pipes. Escaping this session is not supported."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io Reader]
           [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

(defn command [argv]
  (when-not (and (.isDirectory (io/file "/proc")) (.canExecute (io/file "/usr/bin/setsid")))
    (throw (ex-info "Bounded CLI text execution requires Linux setsid/procfs" {:type ::unsupported})))
  (into ["/usr/bin/setsid" "--wait" "--"] argv))

(defn live-members [session]
  (vec
   (keep (fn [^java.io.File dir]
           (when (re-matches #"[0-9]+" (.getName dir))
             (try
               (let [s (java.nio.file.Files/readString (.toPath (io/file dir "stat")))
                     fields (str/split (subs s (+ 2 (.lastIndexOf ^String s ")"))) #"\s+")]
                 (when (and (= (str session) (get fields 3))
                            (not (#{"Z" "X"} (first fields))))
                   (some-> (ProcessHandle/of (Long/parseLong (.getName dir))) (.orElse nil))))
               (catch java.nio.file.NoSuchFileException _ nil))))
         (or (.listFiles (io/file "/proc"))
             (throw (ex-info "Cannot enumerate owned CLI processes" {:type ::scan-unavailable}))))))

(defn stop-session!
  "Return only when no live process remains in this owned session. Failure to
   stop stays outstanding on the provider worker: no timeout certifies exit."
  [^Process process]
  (loop []
    (let [stopped? (try
                     (when (.isAlive process) (.destroyForcibly process))
                     (doseq [^ProcessHandle member (live-members (.pid process))] (.destroyForcibly member))
                     (when (.isAlive process) (.destroyForcibly process))
                     (and (not (.isAlive process)) (empty? (live-members (.pid process))))
                     ;; Enumeration uncertainty retains this worker's ownership.
                     ;; It must not turn into a terminal failed Run/refund.
                     (catch Exception _ false))]
      (if-not stopped?
        (do (try (Thread/sleep 10) (catch InterruptedException _ nil)) (recur))
        (.waitFor process)))))

(defn read-bounded
  "Drain a Reader with a strict character bound. Invoke on-overflow before
   throwing, so another pipe cannot keep the provider blocked indefinitely."
  [^Reader reader limit on-overflow]
  (let [buffer (char-array 4096) result (StringBuilder.)]
    (loop []
      (let [n (.read reader buffer)]
        (if (= -1 n)
          (str result)
          (do
            (when (> (+ (.length result) n) limit)
              (on-overflow)
              (throw (ex-info "CLI output exceeds its capture limit" {:type ::output-limit})))
            (.append result buffer 0 n)
            (recur)))))))

(defn read-line-bounded [^Reader reader remaining]
  (let [out (StringBuilder.)]
    (loop []
      (let [c (.read reader)]
        (when (and (not= -1 c) (neg? (vswap! remaining dec)))
          (throw (ex-info "CLI output exceeds its capture limit" {:type ::output-limit})))
        (cond
          (= -1 c) (when (pos? (.length out)) (str out))
          (= 10 c) (str out)
          :else (do
                  (when (>= (.length out) 2097152)
                    (throw (ex-info "CLI output exceeds its capture limit" {:type ::output-limit})))
                  (.append out (char c))
                  (recur)))))))
