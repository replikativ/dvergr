(ns dvergr.audio.stt-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [dvergr.audio.stt :as stt]
            [hato.client :as hc]
            [taoensso.telemere :as tel]))

(def audio {:bytes (byte-array 10) :mime "audio/wav"})

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
    (println "SKIP ffmpeg chunking: ffmpeg is not on PATH")
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
