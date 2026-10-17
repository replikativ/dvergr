(ns dvergr.model.startup-test
  "Model clients do not initialize a database merely by loading provider code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(deftest model-clients-load-without-database-or-agent-runtime
  ;; Other tests have already loaded these libraries. Only a fresh JVM can
  ;; detect a new eager dependency; no provider or credentials are constructed.
  (let [home (.toAbsolutePath (Files/createTempDirectory "dvergr-model-startup-" (make-array FileAttribute 0)))
        output (.resolve home "output.log")
        classpath (->> (str/split (System/getProperty "java.class.path")
                                  (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
                       (map #(.getAbsolutePath (io/file %)))
                       (str/join java.io.File/pathSeparator))
        expression "(do
          (require 'dvergr.model.gateway 'dvergr.model.chat 'dvergr.model.providers)
          (doseq [n '[datahike.api kontor.resource dvergr.resource.datahike
                      dvergr.agent.attempt.governance dvergr.chat.tool-schema.datahike
                      sci.core org.replikativ.spindel.engine.impl.simple]]
            (assert (nil? (find-ns n)) (str \"Unexpected model dependency: \" n)))
          (println \"model-clients-ready\") (shutdown-agents))"
        builder (doto (ProcessBuilder.
                       ^java.util.List [(str (System/getProperty "java.home") "/bin/java")
                                        "-Xmx256m" "-XX:ActiveProcessorCount=1"
                                        (str "-Duser.home=" home)
                                        "-cp" classpath
                                        "clojure.main" "-e" expression])
                  (.directory (.toFile home))
                  (.redirectErrorStream true)
                  (.redirectOutput (.toFile output)))
        env (.environment builder)]
    (.clear env)
    (.put env "PATH" "/usr/bin:/bin")
    (.put env "HOME" (str home))
    (try
      (let [process (.start builder)]
        (try
          (let [finished? (.waitFor process 60 TimeUnit/SECONDS)]
            (is finished? "Credential-free model loading must finish within its bound")
            (when finished?
              (let [log (slurp (.toFile output))]
                (is (zero? (.exitValue process)) (subs log 0 (min 2048 (count log))))
                (is (= "model-clients-ready\n" log)))))
          (finally
            (when (.isAlive process)
              (.destroyForcibly process)
              (is (.waitFor process 10 TimeUnit/SECONDS)
                  "The stopped startup-test JVM must be reaped before cleanup")))))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists home)))))
