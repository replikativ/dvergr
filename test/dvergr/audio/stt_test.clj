(ns dvergr.audio.stt-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.test-support :as test-support]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dvergr.audio.stt :as stt]
            [dvergr.model.provider-errors :as provider-errors]
            [hato.client :as hc]
            [taoensso.telemere :as tel]))

(def audio {:bytes (byte-array 10) :mime "audio/wav"})

(deftest local-whisper-can-use-an-explicit-host-interpreter
  (let [command (atom nil)]
    (with-redefs [shell/sh (fn [& args] (reset! command args) {:exit 0 :out "test speech"})]
      (binding [stt/*python-executable* "/opt/simmis-python/bin/python3"]
        (is (= "test speech" (#'stt/transcribe-local (byte-array 10) "wav")))
        (is (= "/opt/simmis-python/bin/python3" (first @command)))))))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def http-backends
  [{:name :groq :url "groq" :key-fn (constantly "test")}
   {:name :openai :url "openai" :key-fn (constantly "test")}])

(deftest ordered-backend-failures
  (with-redefs [stt/backends http-backends
                hc/post (fn [url _]
                          (if (= url "groq")
                            {:status 429 :body "rate limited"}
                            (throw (java.io.IOException. "connection refused"))))]
    (is (= {:attempts [{:backend :groq :status 429 :message "HTTP 429: rate limited"}
                       {:backend :openai :status :error :message "connection refused"}]}
           (failure #(stt/transcribe audio))))))

(deftest fallback-logs-failure
  (let [{:keys [value signals error]}
        (tel/with-signals
          (with-redefs [stt/backends http-backends
                        hc/post (fn [url _]
                                  (if (= url "groq")
                                    {:status 503 :body "unavailable"}
                                    {:status 200 :body "{\"text\":\"hello\"}"}))]
            (stt/transcribe audio)))]
    (is (nil? error))
    (is (= "hello" value))
    (is (some #(= {:backend :groq :status 503 :message "HTTP 503: unavailable"}
                  (:data %)) signals))))

(deftest blank-and-unavailable-backends
  (testing "successful silence stays nil"
    (with-redefs [stt/backends http-backends
                  hc/post (fn [_ _] {:status 200 :body "{\"text\":\"  \"}"})]
      (is (nil? (stt/transcribe audio)))))
  (testing "unconfigured backends are not attempts"
    (with-redefs [stt/backends [{:name :groq :key-fn (constantly nil)}]]
      (is (= {:attempts []} (failure #(stt/transcribe audio)))))))

(deftest local-failure-and-native-exception
  (with-redefs [stt/backends [{:name :native :native? true} {:name :local :local? true}]
                stt/transcribe-native (fn [& _] (throw (ex-info "model load failed" {})))
                shell/sh (fn [& _] {:exit 2 :err "missing faster_whisper"})]
    (is (= {:attempts [{:backend :native :status :error :message "model load failed"}
                       {:backend :local :status 2 :message "missing faster_whisper"}]}
           (failure #(stt/transcribe audio))))))

(deftest chunk-validation-and-missing-ffmpeg
  (doseq [chunk [{:max-bytes 44 :seconds 1} {:max-bytes 100 :seconds 0}
                 {:max-bytes 100 :seconds Double/NaN}]]
    (is (:chunk (failure #(stt/transcribe (assoc audio :chunk chunk))))))
  (with-redefs [shell/sh (fn [& _] (throw (java.io.IOException. "not found")))]
    (is (= {:tool :ffmpeg}
           (failure #(stt/transcribe (assoc audio :chunk {:max-bytes 100 :seconds 1})))))))

(defn- ffmpeg? []
  (try (zero? (:exit (shell/sh "ffmpeg" "-version")))
       (catch java.io.IOException _ false)))

(defn- short-wav []
  (let [pcm (byte-array 80000)
        format (javax.sound.sampled.AudioFormat. 16000.0 16 1 true false)]
    (with-open [stream (javax.sound.sampled.AudioInputStream.
                        (java.io.ByteArrayInputStream. pcm) format 40000)
                out (java.io.ByteArrayOutputStream.)]
      (javax.sound.sampled.AudioSystem/write stream javax.sound.sampled.AudioFileFormat$Type/WAVE out)
      (.toByteArray out))))

(deftest generated-wav-chunking
  (if-not (ffmpeg?)
    (test-support/skip! "ffmpeg chunking: ffmpeg is not on PATH")
    (let [wav (short-wav)
          calls (atom [])]
      (with-redefs [stt/backends [{:name :test}]
                    stt/try-backend (fn [_ bytes mime ext]
                                      (swap! calls conj {:bytes bytes :mime mime :ext ext})
                                      (str "part " (count @calls)))]
        (doseq [[chunk expected] [[{:max-bytes 32044 :seconds 10} [1.0 2.0 2.5]]
                                  [{:max-bytes 20000000 :seconds 1} [1.0 2.0 2.5]]]]
          (reset! calls [])
          (let [{:keys [text chunks]} (stt/transcribe {:bytes wav :mime "audio/wav" :chunk chunk})]
            (is (= [0.0 1.0 2.0] (mapv :start-seconds chunks)))
            (is (= expected (mapv :end-seconds chunks)))
            (is (= ["part 1" "part 2" "part 3"] (mapv :text chunks)))
            (is (= "[0.0s] part 1\n[1.0s] part 2\n[2.0s] part 3" text))
            (is (every? #(<= (alength ^bytes (:bytes %)) (:max-bytes chunk)) @calls))
            (is (every? #(= ["audio/wav" "wav"] [(:mime %) (:ext %)]) @calls))
            (is (= 80000 (reduce + (map #(- (alength ^bytes (:bytes %)) 44) @calls)))))))
      (with-redefs [stt/backends [{:name :test}]
                    stt/try-backend (fn [& _] " ")]
        (let [{:keys [text chunks]} (stt/transcribe {:bytes wav :mime "audio/wav"
                                                     :chunk {:max-bytes 32044 :seconds 1}})]
          (is (nil? text))
          (is (= 3 (count chunks)))
          (is (every? #(nil? (:text %)) chunks))))
      (with-redefs [stt/backends [{:name :test}]
                    stt/try-backend (fn [& _] (throw (ex-info "failed" {:status 500})))]
        (is (= {:chunk {:start-seconds 0.0 :end-seconds 1.0}
                :attempts [{:backend :test :status 500 :message "failed"}]}
               (failure #(stt/transcribe {:bytes wav :mime "audio/wav"
                                          :chunk {:max-bytes 32044 :seconds 1}}))))))))

(deftest chunk-decoding-errors-and-cleanup
  (let [paths (atom [])]
    (with-redefs [shell/sh (fn [& args]
                             (if (= "-version" (second args))
                               {:exit 0}
                               (do (reset! paths [(nth args 7) (last args)])
                                   {:exit 1 :err "invalid audio"})))]
      (is (= {:tool :ffmpeg :status 1}
             (failure #(stt/transcribe (assoc audio :chunk {:max-bytes 32044 :seconds 1}))))))
    (is (= 2 (count @paths)))
    (is (every? #(not (.exists (java.io.File. ^String %))) @paths))))

(deftest default-does-not-invoke-ffmpeg
  (with-redefs [shell/sh (fn [& _] (throw (AssertionError. "unexpected ffmpeg")))
                stt/backends http-backends
                hc/post (fn [& _] {:status 200 :body "{\"text\":\"hello\"}"})]
    (is (= "hello" (stt/transcribe audio)))))

(def secret-diagnostic
  (str "echoed opaque-credential\n"
       "Authorization: Basic header-password\n"
       "{\"authorization\":\"Bearer json-password\", \"api_key\":\"json-key\"}\n"
       "Bearer bearer-password\n"
       "{:authorization \"Basic edn-password\"}\n"
       "X-API-Key: header-key\n"
       "https://example.invalid/?access_token=query-token&X-Amz-Signature=query-signature&ok=yes\n"
       "api-key=assignment-key sk-providerkey gsk_providerkey fw_providerkey\n"
       (apply str (repeat 5000 "x")) "END-OF-DIAGNOSTIC"))

(def diagnostic-secrets
  ["opaque-credential" "header-password" "json-password" "json-key" "bearer-password"
   "edn-password" "header-key" "query-token" "query-signature" "assignment-key"
   "sk-providerkey" "gsk_providerkey" "fw_providerkey" "END-OF-DIAGNOSTIC"])

(deftest secret-bearing-failures
  (doseq [mode [:response :http-exception :native-exception :local-stderr]]
    (testing (str mode)
      (let [{:keys [value signals error]}
            (tel/with-signals
              (with-redefs [stt/backends (case mode
                                           :native-exception [{:name :native :native? true}]
                                           :local-stderr [{:name :local :local? true}]
                                           [{:name :groq :url "stub" :key-fn (constantly "opaque-credential")}])
                            hc/post (fn [& _]
                                      (if (= mode :response)
                                        {:status 401 :body secret-diagnostic}
                                        (throw (java.io.IOException. secret-diagnostic))))
                            stt/transcribe-native (fn [& _]
                                                    (throw (ex-info (str/replace secret-diagnostic
                                                                                 "opaque-credential" "api_key=native-key") {})))
                            shell/sh (fn [& _] {:exit 1 :err (str/replace secret-diagnostic
                                                                          "opaque-credential" "token=local-token")})]
                (failure #(stt/transcribe audio))))
            attempts (:attempts value)
            logs (filter #(= :dvergr.audio.stt/backend-failed (:id %)) signals)]
        (is (nil? error))
        (is (= 1 (count attempts)))
        (is (= attempts (mapv :data logs)))
        (is (<= (count (:message (first attempts))) 2048))
        (is (str/includes? (:message (first attempts)) "[REDACTED]"))
        (is (str/ends-with? (:message (first attempts)) "...[truncated]"))
        (doseq [secret (conj diagnostic-secrets "native-key" "local-token")]
          (is (not (str/includes? (pr-str value) secret)))
          (is (not (str/includes? (pr-str signals) secret))))))))

(def chunk-options (assoc audio :chunk {:max-bytes 32044 :seconds 1}))

(deftest private-chunk-directory-and-cleanup
  (let [directories (atom [])]
    (doseq [mode [:success :backend-failure :decoder-failure :first-allocation :partial-allocation]]
      (testing (str mode)
        (let [paths (atom [])
              create-file @#'stt/chunk-file
              create-directory @#'stt/chunk-directory
              {:keys [value error]}
              (tel/with-signals
                (with-redefs [stt/chunk-directory (fn []
                                                    (let [directory (create-directory)]
                                                      (swap! directories conj directory)
                                                      directory))
                              stt/chunk-file (fn [directory filename]
                                               (is (= (last @directories) directory))
                                               (when (.supportsFileAttributeView
                                                      (java.nio.file.Files/getFileStore directory) "posix")
                                                 (is (= "rwx------"
                                                        (java.nio.file.attribute.PosixFilePermissions/toString
                                                         (java.nio.file.Files/getPosixFilePermissions
                                                          directory (make-array java.nio.file.LinkOption 0))))))
                                               (when (or (= mode :first-allocation)
                                                         (and (= mode :partial-allocation) (= filename "output.raw")))
                                                 (throw (java.io.IOException. "allocation failed")))
                                               (let [path (create-file directory filename)]
                                                 (swap! paths conj path)
                                                 path))
                              shell/sh (fn [& args]
                                         (if (= "-version" (second args))
                                           {:exit 0}
                                           (do
                                             (is (= 2 (count @paths)))
                                             (is (= (.getParent (first @paths)) (.getParent (second @paths))))
                                             (java.nio.file.Files/write (second @paths) (byte-array 20)
                                                                        (make-array java.nio.file.OpenOption 0))
                                             {:exit (if (= mode :decoder-failure) 1 0) :err "decode failed"})))
                              stt/backends [{:name :test}]
                              stt/try-backend (fn [& _]
                                                (if (= mode :backend-failure)
                                                  (throw (ex-info "backend failed" {}))
                                                  "hello"))]
                  (stt/transcribe chunk-options)))]
          (if (= mode :success)
            (do (is (nil? error)) (is (= "[0.0s] hello" (:text value))))
            (is (some? error)))
          (is (= (case mode :first-allocation 0 :partial-allocation 1 2) (count @paths)))
          (is (every? #(not (java.nio.file.Files/exists % (make-array java.nio.file.LinkOption 0)))
                      (conj @paths (last @directories)))))))
    (is (= 5 (count (distinct @directories))))))

(deftest cleanup-failures-are-logged-and-do-not-mask-result
  (let [deleted (atom [])
        delete-path @#'stt/delete-chunk-path!
        {:keys [value signals error]}
        (tel/with-signals
          (with-redefs [stt/delete-chunk-path! (fn [path]
                                                 (delete-path path)
                                                 (swap! deleted conj path)
                                                 (when (= "input" (str (.getFileName path)))
                                                   (throw (java.io.IOException. "token=cleanup-secret"))))
                        shell/sh (fn [& _] {:exit 0})]
            (stt/transcribe chunk-options)))
        logs (filter #(= :dvergr.audio.stt/cleanup-failed (:id %)) signals)]
    (is (nil? error))
    (is (= {:text nil :chunks []} value))
    (is (= 3 (count @deleted)))
    (is (= 1 (count logs)))
    (is (= "token=[REDACTED]" (get-in (first logs) [:data :message])))
    (is (not (str/includes? (pr-str signals) "cleanup-secret")))))

(deftest provider-diagnostics-strip-url-credentials
  (let [message "Failed https://USER:PASSWORD@example.com/v1?api_key=QUERY#FRAGMENT X-Api-Key: HEADER"
        sanitized (provider-errors/sanitize-message message)]
    (is (str/includes? sanitized "https://example.com/v1"))
    (doseq [secret ["USER" "PASSWORD" "QUERY" "FRAGMENT" "HEADER"]]
      (is (not (str/includes? sanitized secret))))))
