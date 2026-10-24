(ns dvergr.catalog.fixture-pack
  "Bounded inert text fixtures, separate from the authoring repository's files.
   Decoding supplies the existing fixture maps; it grants no execution authority."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dvergr.rooms.files :as files])
  (:import [java.io PushbackReader StringReader]
           [java.nio CharBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]))

(def max-pack-bytes (* 512 1024))
(def max-file-bytes (* 512 1024))
(def max-files 100)
(def max-decoded-bytes (* 2 1024 1024))

(defn- refuse! [path]
  (throw (ex-info "Invalid workflow fixture pack" {:type ::invalid :file path})))

(defn- utf8-size! [path text maximum]
  (when-not (and (string? text) (<= (count text) maximum)) (refuse! path))
  (let [encoder (doto (.newEncoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))
        size (.remaining (.encode encoder (CharBuffer/wrap ^String text)))]
    (when (> size maximum) (refuse! path))
    size))

(defn- bounded-syntax! [path text]
  ;; This closed data format needs maps, short scalar tokens and strings only.
  ;; Bound recursive reader work BEFORE edn/read, including metadata/discard
  ;; prefixes which can recurse without adding collection delimiters.
  (loop [i 0 depth 0 quoted? false escaped? false comment? false token 0]
    (when (< i (count text))
      (let [c (.charAt ^String text i)]
        (cond
          comment? (recur (inc i) depth false false (not (#{\newline \return} c)) 0)
          quoted? (cond
                    escaped? (recur (inc i) depth true false false 0)
                    (= c \\) (recur (inc i) depth true true false 0)
                    (= c \") (recur (inc i) depth false false false 0)
                    :else (recur (inc i) depth true false false 0))
          (= c \;) (recur (inc i) depth false false true 0)
          (= c \") (recur (inc i) depth true false false 0)
          (= c \{) (do (when (>= depth 8) (refuse! path))
                       (recur (inc i) (inc depth) false false false 0))
          (= c \}) (do (when (zero? depth) (refuse! path))
                       (recur (inc i) (dec depth) false false false 0))
          (#{\# \\ \^ \' \@ \` \~ \( \) \[ \]} c) (refuse! path)
          (or (= c \,) (Character/isWhitespace c)) (recur (inc i) depth false false false 0)
          :else (do (when (>= token 256) (refuse! path))
                    (recur (inc i) depth false false false (inc token))))))))

(defn- read-pack! [path text]
  (try
    (utf8-size! path text max-pack-bytes)
    (bounded-syntax! path text)
    (with-open [reader (PushbackReader. (StringReader. text))]
      (let [eof (Object.)
            options {:eof eof :readers {} :default (fn [& _] (refuse! path))}
            value (edn/read options reader)
            entries (:files value)]
        (when-not (and (identical? eof (edn/read options reader))
                       (map? value) (= #{:kind :version :files} (set (keys value)))
                       (= :dvergr/fixture-pack (:kind value)) (= 1 (:version value))
                       (map? entries) (<= (count entries) max-files))
          (refuse! path))
        (doseq [[target body] entries]
          (when-not (and (string? target) (<= 1 (count target) 512)
                         (= target (files/workspace-path target)))
            (refuse! path))
          (utf8-size! path target 2048)
          (utf8-size! path body max-file-bytes))
        (into (sorted-map) entries)))
    (catch Exception _ (refuse! path))))

(defn decode
  "Validate ALL root/case packs, including unselected cases. Return decoded
   shared/case fixture maps. Same-scope directory collisions are refused;
   ordinary case-over-shared overrides remain the catalog's responsibility.
   The aggregate bound applies to packed bytes only, preserving legacy bundles."
  [source]
  (let [packs (vec (take (inc max-files)
                         (keep (fn [[path text]]
                                 (cond
                                   (= path "fixture-pack.edn") [nil path text]
                                   :else (when-let [[_ case-id] (re-matches #"cases/([^/]+)/fixture-pack\.edn" path)]
                                           [case-id path text]))) source)))]
    (when (> (count packs) max-files) (refuse! "fixture-pack.edn"))
    (let [[decoded _] (reduce
                       (fn [[out total] [case-id path text]]
                         (when (and case-id (> (count case-id) 100)) (refuse! path))
                         (when-not (= (str "/" path) (files/workspace-path path)) (refuse! path))
                         (let [entries (read-pack! path text)
                               prefix (if case-id (str "cases/" case-id "/fixtures/") "fixtures/")
                               plain (into #{} (keep (fn [[p _]]
                                                       (when (str/starts-with? p prefix)
                                                         (files/workspace-path (str "/" (subs p (count prefix))))))) source)
                               total (+ total (reduce + 0 (map #(utf8-size! path % max-file-bytes) (vals entries))))]
                           (when (seq (set/intersection plain (set (keys entries)))) (refuse! path))
                           (when (> total max-decoded-bytes) (refuse! path))
                           [(assoc out case-id entries) total])) [{} 0] packs)]
      {:shared (get decoded nil {}) :cases (dissoc decoded nil)})))
