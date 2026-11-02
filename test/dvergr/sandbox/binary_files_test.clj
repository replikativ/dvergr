(ns dvergr.sandbox.binary-files-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [dvergr.effects :as effects]
            [dvergr.io.binary :as binary]
            [dvergr.sandbox.ns.io :as io]
            [hato.client :as http]
            [muschel.fs :as mfs]
            [muschel.fs.geschichte :as gfs]
            [sci.core :as sci])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.zip ZipOutputStream ZipEntry]))

(defn- zip-bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[name data] entries]
        (.putNextEntry zip (ZipEntry. name))
        (.write zip ^bytes data)
        (.closeEntry zip)))
    (.toByteArray out)))

(def ^:private payload (byte-array (map unchecked-byte (range 256))))
(defn- same-bytes? [a b] (= (vec a) (vec b)))

(deftest binary-receipts-digest-content-not-array-identity
  (is (= (effects/digest payload) (effects/digest (aclone payload))))
  (is (not= (effects/digest payload) (effects/digest (byte-array [0 1 2])))))

(defn- seed! [filesystem path data]
  (with-open [out (mfs/open-sink filesystem path false)]
    (.write ^java.io.OutputStream out ^bytes data)))

(defn- with-repository [f]
  (let [{:keys [close!] :as repo} (gfs/memory-repository! {:name (str "binary-" (random-uuid))})
        filesystem (gfs/make-root repo)
        ctx (sci/init {})]
    (try
      (io/add-fs-ns! ctx :filesystem filesystem)
      (f ctx filesystem)
      (finally (close!)))))

(deftest http-preserves-bytes-and-closes-stream
  (doseq [mode [:bytes :byte-array]]
    (let [ctx (sci/init {}) closed? (atom false) calls (atom 0)]
      (io/add-http-ns! ctx)
      (with-redefs [http/request
                    (fn [opts]
                      (swap! calls inc)
                      (is (= :stream (:as opts)))
                      (is (= :never (get-in opts [:http-client :redirect-policy])))
                      {:status 200 :headers {}
                       :body (proxy [ByteArrayInputStream] [payload]
                               (close [] (reset! closed? true)))})]
        (is (same-bytes? payload (:body (sci/eval-string* ctx
                                                          (str "(babashka.http-client/get \"https://93.184.216.34/file.zip\" {:as " mode "})")))))
        (is @closed?)
        (reset! closed? false)
        (is (thrown-with-msg? Exception #"size limit"
                              (sci/eval-string* ctx "(babashka.http-client/get \"https://93.184.216.34/file.zip\" {:as :bytes :max-bytes 10})")))
        (is @closed? "oversized response also closes")
        (is (= 2 @calls) "no automatic retry")
        (is (thrown-with-msg? Exception #"Unsupported HTTP response format"
                              (sci/eval-string* ctx "(babashka.http-client/get \"https://93.184.216.34/x\" {:as :stream})")))
        (is (= 2 @calls) "unsupported formats do not send a request")))))

(deftest binary-secret-reflection-is-refused-without-decoding
  (let [scrub @#'io/scrub-response
        secrets {"test" {:value "secret-value" :placeholder "@@secret:test@@"}}
        reflected (byte-array (concat [0 -1 -2] (seq (.getBytes "secret-value" "UTF-8"))))]
    (is (thrown-with-msg? Exception #"reflects a configured secret"
                          (scrub secrets {:body reflected :headers {}})))
    (is (same-bytes? payload (:body (scrub secrets {:body payload :headers {"echo" "secret-value"}}))))
    (is (= "@@secret:test@@" (get-in (scrub secrets {:body payload :headers {"echo" "secret-value"}}) [:headers "echo"])))))

(deftest binary-fixtures-have-the-same-size-and-lifetime-contract
  (let [ctx (sci/init {})]
    (io/add-http-ns! ctx :fixture-transport (fn [_] {:status 200 :headers {} :body payload}))
    (is (same-bytes? payload (:body (sci/eval-string* ctx "(babashka.http-client/get \"https://fixture.test/x\" {:as :bytes})"))))
    (is (thrown-with-msg? Exception #"size limit"
                          (sci/eval-string* ctx "(babashka.http-client/get \"https://fixture.test/x\" {:as :bytes :max-bytes 10})")))
    (is (thrown-with-msg? Exception #"Unsupported HTTP response format"
                          (sci/eval-string* ctx "(babashka.http-client/get \"https://fixture.test/x\" {:as :stream})")))))

(deftest corrupt-and-empty-archives
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/empty.zip" (zip-bytes []))
      (is (= "empty" (sci/eval-string* ctx "(babashka.fs/unzip \"empty.zip\" \"empty\")")))
      (let [archive (zip-bytes [["entry" payload]])
            ;; Corrupt the local compressed payload; preflight must read it.
            broken (aclone archive)]
        (aset-byte broken 40 (unchecked-byte (bit-xor 255 (aget broken 40))))
        (seed! filesystem "/broken.zip" broken)
        (is (thrown? Exception (sci/eval-string* ctx "(babashka.fs/unzip \"broken.zip\" \"broken\")")))
        (is (not (mfs/exists? filesystem "/broken")))))))

(deftest truncated-zip-structure-is-not-an-empty-success
  (doseq [data [(byte-array [80 75 3 4]) (byte-array [80 75 5 6])
                (let [zip (zip-bytes [["entry" payload]])]
                  (java.util.Arrays/copyOf zip (- (alength zip) 12)))]]
    (with-repository
      (fn [ctx filesystem]
        (seed! filesystem "/truncated.zip" data)
        (is (thrown? Exception (sci/eval-string* ctx "(babashka.fs/unzip \"truncated.zip\" \"output\")")))
        (is (not (mfs/exists? filesystem "/output")))))))

(deftest explicit-selection-does-not-bypass-expansion-limits
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/source.zip" (zip-bytes [["keep" payload] ["raw/T:\\odd.pdf" payload]]))
      (is (= "selected"
             (sci/eval-string* ctx "(babashka.fs/unzip \"source.zip\" \"selected\" {:extract-fn (fn [entry] (= \"keep\" (:name entry)))})")))
      (is (same-bytes? payload (mfs/read-bytes filesystem "/selected/keep")))
      (is (thrown-with-msg? Exception #"size limit"
                            (sci/eval-string* ctx "(babashka.fs/unzip \"source.zip\" \"bounded\" {:extract-fn (constantly false) :max-total-bytes 300})")))
      (is (not (mfs/exists? filesystem "/bounded"))))))

(deftest recorded-text-and-bytes-are-distinct-and-owned
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/source" (.getBytes "hello" "UTF-8"))
      (let [recording (effects/recording!) replay (atom nil)]
        (try
          (io/add-fs-ns! ctx :filesystem filesystem
                         :effects (constantly {:handlers (effects/handlers [[:record {:id recording}]])}))
          (is (= "hello" (sci/eval-string* ctx "(slurp \"source\")")))
          (let [bytes (sci/eval-string* ctx "(babashka.fs/read-all-bytes \"source\")")]
            (aset-byte bytes 0 (unchecked-byte 0)))
          (let [[text binary :as entries] (effects/recorded recording)]
            (is (not= (:key text) (:key binary)))
            (is (= "hello" (String. ^bytes (:value binary) "UTF-8")))
            (reset! replay (effects/replay! [binary binary text])))
          (io/add-fs-ns! ctx :filesystem filesystem
                         :effects (constantly {:handlers (effects/handlers [[:replay {:id @replay}]])}))
          (let [bytes (sci/eval-string* ctx "(babashka.fs/read-all-bytes \"source\")")]
            (aset-byte bytes 0 (unchecked-byte 0)))
          (is (= "hello" (String. ^bytes (sci/eval-string* ctx "(babashka.fs/read-all-bytes \"source\")") "UTF-8")))
          (is (= "hello" (sci/eval-string* ctx "(slurp \"source\")")))
          (finally (effects/release! recording) (when @replay (effects/release! @replay))))))))

(deftest writes-freeze-the-caller-array-before-effect-handlers
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/source" (.getBytes "hello" "UTF-8"))
      (let [input (sci/eval-string* ctx "(def input (babashka.fs/read-all-bytes \"source\")) input")
            handler (fn [effect next]
                      (when (= :fs/write (:effect effect)) (aset-byte input 0 (unchecked-byte 0)))
                      (next effect))]
        (io/add-fs-ns! ctx :filesystem filesystem :effects (constantly {:handlers [handler]}))
        (sci/eval-string* ctx "(babashka.fs/write-bytes \"target\" input)")
        (is (= "hello" (mfs/read-file filesystem "/target")))))))

(deftest exact-binary-roundtrip-and-unzip-in-geschichte
  (with-repository
    (fn [ctx filesystem]
      (io/add-http-ns! ctx)
      (let [archive (zip-bytes [["dataset/raw.bin" payload] ["dataset/note.txt" (.getBytes "hello" "UTF-8")]])]
        (with-redefs [http/request (fn [_] {:status 200 :headers {} :body (ByteArrayInputStream. archive)})]
          (is (= "data/cases.zip"
                 (sci/eval-string* ctx "(require '[babashka.fs :as fs] '[babashka.http-client :as http]) (let [r (http/get \"https://93.184.216.34/cases.zip\" {:as :bytes})] (fs/write-bytes \"data/cases.zip\" (:body r)))"))))
        (is (same-bytes? archive (sci/eval-string* ctx "(fs/read-all-bytes \"data/cases.zip\")")))
        (is (= "data/unpacked" (sci/eval-string* ctx "(fs/unzip \"data/cases.zip\" \"data/unpacked\")")))
        (is (same-bytes? payload (mfs/read-bytes filesystem "/data/unpacked/dataset/raw.bin")))
        (is (= "hello" (sci/eval-string* ctx "(slurp \"data/unpacked/dataset/note.txt\")")))
        (is (thrown-with-msg? Exception #"already exists"
                              (sci/eval-string* ctx "(fs/unzip \"data/cases.zip\" \"data/unpacked\")")))
        (is (= "data/unpacked" (sci/eval-string* ctx "(fs/unzip \"data/cases.zip\" \"data/unpacked\" {:replace-existing true})")))
        (is (thrown-with-msg? Exception #"only :append"
                              (sci/eval-string* ctx "(fs/write-bytes \"x\" (fs/read-all-bytes \"data/cases.zip\") {:create-new true})")))))))

(deftest archive-preflight-refuses-unsafe-paths-without-writing
  (doseq [bad ["../escape" "/absolute" "C:/drive" "a\\b" "dir/../../escape" ".git/config"]]
    (with-repository
      (fn [ctx filesystem]
        (seed! filesystem "/bad.zip" (zip-bytes [["fine.txt" payload] [bad payload]]))
        (is (thrown? Exception (sci/eval-string* ctx "(babashka.fs/unzip \"bad.zip\" \"output\")")) bad)
        (is (not (mfs/exists? filesystem "/output")) "preflight writes nothing")))))

(deftest archive-expansion-and-collision-limits
  (doseq [[entries options] [[[["a" payload]] "{:max-entry-bytes 10}"]
                             [[["a" payload] ["b" payload]] "{:max-total-bytes 300}"]
                             [[["a" payload] ["b" payload]] "{:max-entries 1}"]
                             [[["a" payload] ["a/b" payload]] "{}"]
                             [[["a/b" payload] ["a" payload]] "{}"]
                             [[["a" payload] ["./a" payload]] "{}"]]]
    (with-repository
      (fn [ctx filesystem]
        (seed! filesystem "/bad.zip" (zip-bytes entries))
        (is (thrown? Exception (sci/eval-string* ctx (str "(babashka.fs/unzip \"bad.zip\" \"output\" " options ")"))))
        (is (not (mfs/exists? filesystem "/output")))))))

(deftest archive-input-is-frozen-before-preflight
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/source.zip" (zip-bytes [["original" payload]]))
      (let [changed? (atom false)
            handler (fn [effect next]
                      (when (and (= :fs/stat (:effect effect)) (compare-and-set! changed? false true))
                        (seed! filesystem "/source.zip" (zip-bytes [["replacement" payload]])))
                      (next effect))]
        (io/add-fs-ns! ctx :filesystem filesystem :effects (constantly {:handlers [handler]}))
        (sci/eval-string* ctx "(babashka.fs/unzip \"source.zip\" \"output\")")
        (is @changed?)
        (is (same-bytes? payload (mfs/read-bytes filesystem "/output/original")))
        (is (not (mfs/exists? filesystem "/output/replacement")))))))

(deftest archive-writes-retain-byte-quotas-and-no-overwrite-checks
  (with-repository
    (fn [ctx filesystem]
      (seed! filesystem "/source.zip" (zip-bytes [["entry" payload]]))
      (let [quota (effects/quota! {:bytes 10})]
        (try
          (io/add-fs-ns! ctx :filesystem filesystem
                         :effects (constantly {:handlers (effects/handlers [[:quota {:id quota}]])}))
          (is (thrown-with-msg? Exception #"quota"
                                (sci/eval-string* ctx "(babashka.fs/unzip \"source.zip\" \"output\")")))
          (is (not (mfs/exists? filesystem "/output/entry")))
          (finally (effects/release! quota))))
      (let [handler (fn [effect next]
                      (when (= :fs/write (:effect effect))
                        (seed! filesystem "/output/entry" (.getBytes "concurrent" "UTF-8")))
                      (next effect))]
        (io/add-fs-ns! ctx :filesystem filesystem :effects (constantly {:handlers [handler]}))
        (is (thrown-with-msg? Exception #"already exists"
                              (sci/eval-string* ctx "(babashka.fs/unzip \"source.zip\" \"output\")")))
        (is (= "concurrent" (mfs/read-file filesystem "/output/entry")))))))

(deftest saved-binary-functions-select-the-current-filesystem
  (with-repository
    (fn [ctx first-fs]
      (with-repository
        (fn [_ second-fs]
          (seed! first-fs "/value" payload)
          (seed! second-fs "/value" (.getBytes "second" "UTF-8"))
          (let [selected (atom first-fs)]
            (io/add-fs-ns! ctx :filesystem-resolver #(deref selected))
            (sci/eval-string* ctx "(def saved-read babashka.fs/read-all-bytes) (def saved-write babashka.fs/write-bytes)")
            (reset! selected second-fs)
            (sci/eval-string* ctx "(saved-write \"copy\" (saved-read \"value\"))")
            (is (not (mfs/exists? first-fs "/copy")))
            (is (= "second" (mfs/read-file second-fs "/copy")))))))))

(deftest physical-adapter-has-the-same-binary-and-path-contract
  (let [dir (fs/create-temp-dir {:prefix "binary-sandbox-"})
        ctx (sci/init {})]
    (try
      (fs/write-bytes (fs/path dir "cases.zip") (zip-bytes [["nested/file" payload]]))
      (io/add-fs-ns! ctx :base-path (str dir))
      (sci/eval-string* ctx "(babashka.fs/unzip \"cases.zip\" \"out\")")
      (is (same-bytes? payload (fs/read-all-bytes (fs/path dir "out/nested/file"))))
      (is (thrown? Exception (sci/eval-string* ctx "(babashka.fs/write-bytes \"../escape\" (babashka.fs/read-all-bytes \"cases.zip\"))")))
      (is (thrown? Exception (sci/eval-string* ctx "(babashka.fs/write-bytes \".git/config\" (babashka.fs/read-all-bytes \"cases.zip\"))")))
      (finally (fs/delete-tree dir)))))
