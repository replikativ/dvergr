(ns dvergr.io.archive
  "Checked ZIP extraction. A private temporary archive and all streams have lexical lifetimes; no host handle is exposed to SCI."
  (:require [clojure.string :as str]
            [dvergr.io.binary :as binary])
  (:import [java.nio.file Files Path OpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip ZipFile CRC32]))

(defn options [opts]
  (when-not (and (or (nil? opts) (map? opts))
                 (every? #{:replace-existing :extract-fn :max-entry-bytes :max-total-bytes :max-entries}
                         (keys opts))
                 (or (nil? (:extract-fn opts)) (ifn? (:extract-fn opts)))
                 (or (not (contains? opts :replace-existing))
                     (boolean? (:replace-existing opts))))
    (throw (ex-info "Unsupported unzip options" {})))
  (let [opts (merge {:replace-existing false :max-entry-bytes (* 64 1024 1024)
                     :max-total-bytes (* 512 1024 1024) :max-entries 10000} opts)]
    (doseq [[k ceiling] [[:max-entry-bytes binary/max-bytes]
                         [:max-total-bytes (* 512 1024 1024)] [:max-entries 10000]]]
      (binary/checked-limit (get opts k) ceiling))
    opts))

(defn- entry-path [s]
  (when (or (str/blank? s) (> (count s) 4096)
            (str/starts-with? s "/") (str/includes? s "\\")
            (str/includes? s "\u0000") (re-find #"^[A-Za-z]:" s)
            (some #{".."} (str/split s #"/")))
    (throw (ex-info "Unsafe ZIP entry path" {:type ::unsafe-path})))
  (let [parts (remove #{"" "."} (str/split s #"/"))]
    (when (or (empty? parts) (> (count parts) 64))
      (throw (ex-info "Invalid ZIP entry path" {:type ::unsafe-path})))
    (str/join "/" parts)))

(defn- add-path [paths path directory?]
  (let [parts (str/split path #"/")
        parents (map #(str/join "/" (take % parts)) (range 1 (count parts)))]
    (when (or (some #(= :file (get paths %)) parents)
              (contains? paths path))
      ;; Implicit parents are directories too. An explicit directory after its
      ;; children is harmless, but duplicate explicit entries are checked below.
      (when-not (and directory? (= :implicit-dir (get paths path))
                     (not-any? #(= :file (get paths %)) parents))
        (throw (ex-info "Conflicting ZIP entry paths" {:type ::path-collision}))))
    (assoc (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 :implicit-dir)) paths parents)
           path (if directory? :dir :file))))

(defn with-zip
  "Freeze bounded input once into a private temporary file, scoped to f.
   ZipFile validates the central directory; entry CRCs are checked separately.
   The same open archive is used for preflight and extraction."
  [^bytes data f]
  (let [path (Files/createTempFile "dvergr-archive-" ".zip" (make-array FileAttribute 0))]
    (try
      (Files/write path data (make-array OpenOption 0))
      (with-open [zip (try (ZipFile. (.toFile path))
                           (catch java.io.IOException e
                             (throw (ex-info "Invalid or incomplete ZIP archive" {:type ::invalid-zip} e))))]
        (f zip))
      (finally (Files/deleteIfExists path)))))

(defn preflight
  "Fully validate the archive's central directory entries, actual expansion
   lengths and CRCs. check-path! inspects policy but must not write."
  [^ZipFile zip opts check-path!]
  (let [buffer (byte-array 8192)]
    (loop [pending (enumeration-seq (.entries zip)) entries [] paths {} total 0]
      (if-let [entry (first pending)]
        (let [name (.getName ^java.util.zip.ZipEntry entry)
              dir? (.isDirectory ^java.util.zip.ZipEntry entry)
              selected? (or dir? (nil? (:extract-fn opts))
                            (boolean ((:extract-fn opts) {:name name :size (.getSize ^java.util.zip.ZipEntry entry)})))
              path (when selected? (entry-path name))]
          (when (>= (count entries) (:max-entries opts))
            (throw (ex-info "ZIP entry count exceeds limit" {:type ::too-many-entries})))
          (let [paths (if selected? (add-path paths path dir?) paths)
                crc (CRC32.)
                size (with-open [input (.getInputStream zip entry)]
                       (loop [size 0]
                         (let [n (.read input buffer)]
                           (if (neg? n) size
                               (let [size (+ size n)]
                                 (when (or (> size (:max-entry-bytes opts))
                                           (> (+ total size) (:max-total-bytes opts)))
                                   (throw (ex-info "Expanded ZIP exceeds size limit" {:type ::too-large})))
                                 (.update crc buffer 0 n)
                                 (recur size))))))]
            (when-not (and (= size (.getSize ^java.util.zip.ZipEntry entry))
                           (= (.getValue crc) (.getCrc ^java.util.zip.ZipEntry entry)))
              (throw (ex-info "ZIP entry length or CRC is invalid" {:type ::invalid-zip})))
            (when (and dir? (pos? size))
              (throw (ex-info "ZIP directory contains data" {:type ::invalid-zip})))
            (when selected? (check-path! path dir?))
            (recur (next pending)
                   (conj entries {:path path :directory? dir? :selected? selected? :size size :entry entry})
                   paths (+ total size))))
        entries))))

(defn extract!
  "Extract the same open private archive. Only one bounded entry is allocated
   at a time; destination effects belong to the caller."
  [^ZipFile zip entries mkdir! write!]
  (doseq [{:keys [path directory? size entry selected?]} entries :when selected?]
    (if directory?
      (mkdir! path)
      (let [data (with-open [input (.getInputStream zip entry)]
                   (binary/read-bounded input (max 1 size)))]
        (when-not (= size (alength ^bytes data))
          (throw (ex-info "ZIP entry changed after preflight" {:type ::changed-input})))
        (write! path data)))))
