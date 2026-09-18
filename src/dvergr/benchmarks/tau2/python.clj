(ns dvergr.benchmarks.tau2.python
  "Python 3.12 value semantics that transcribed tau2 tools expose to agents.

   Tool outputs are Python strings built with f-strings, `str()`/`repr()`,
   `json.loads`/`json.dumps`, and `format(x, '.2f')`; error messages are
   Python exception texts. Agents read these strings, so a transcription is
   only faithful if it renders them identically. Values are Clojure data:
   strings, longs/BigInts (Python int), doubles (Python float), booleans,
   nil (None), vectors (list), and insertion-ordered maps (dict)."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.pyjson :as pj])
  (:import [java.math BigDecimal RoundingMode]))

;; ---------------------------------------------------------------------------
;; Errors

(defn raise
  "Raise a Python exception. `type` is the Python class name; env wrappers
   render it as `Error: <message>`."
  ([message] (raise "Exception" message))
  ([type message]
   (throw (ex-info message {::exception type ::message message}))))

(defn py-exception? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (contains? (ex-data e) ::message)))

(defn exception-message [e] (::message (ex-data e)))
(defn exception-type [e] (::exception (ex-data e)))

(defn type-name
  "Python `type(x).__name__`."
  [x]
  (cond
    (nil? x) "NoneType"
    (boolean? x) "bool"
    (string? x) "str"
    (integer? x) "int"
    (float? x) "float"
    (map? x) "dict"
    (sequential? x) "list"
    (set? x) "set"
    :else (.getSimpleName (class x))))

;; ---------------------------------------------------------------------------
;; repr / str

(defn- printable? [^long cp]
  (let [t (Character/getType (int cp))]
    (not (or (= cp 0x7f)
             (< cp 0x20)
             (#{Character/CONTROL Character/FORMAT Character/SURROGATE
                Character/PRIVATE_USE Character/UNASSIGNED
                Character/LINE_SEPARATOR Character/PARAGRAPH_SEPARATOR} (byte t))
             (and (= t Character/SPACE_SEPARATOR) (not= cp 0x20))))))

(defn str-repr
  "Python `repr(str)`."
  [^String s]
  (let [quote (if (and (str/includes? s "'") (not (str/includes? s "\""))) \" \')
        sb (StringBuilder.)]
    (.append sb quote)
    (doseq [cp (iterator-seq (.iterator (.codePoints s)))]
      (let [cp (long cp)]
        (cond
          (= cp (int quote)) (.append sb (str "\\" quote))
          (= cp 0x5c) (.append sb "\\\\")
          (= cp 0x0a) (.append sb "\\n")
          (= cp 0x0d) (.append sb "\\r")
          (= cp 0x09) (.append sb "\\t")
          (printable? cp) (.appendCodePoint sb (int cp))
          (< cp 0x100) (.append sb (format "\\x%02x" cp))
          (< cp 0x10000) (.append sb (format "\\u%04x" cp))
          :else (.append sb (format "\\U%08x" cp)))))
    (.append sb quote)
    (.toString sb)))

(defn float-repr [^double x]
  (cond
    (Double/isNaN x) "nan"
    (Double/isInfinite x) (if (pos? x) "inf" "-inf")
    :else (pj/py-float-repr x)))

(declare py-repr)

(defn py-str
  "Python `str(x)` (also what an f-string `{x}` renders)."
  [x]
  (if (string? x) x (py-repr x)))

(defn py-repr
  "Python `repr(x)` for data values."
  [x]
  (cond
    (nil? x) "None"
    (true? x) "True"
    (false? x) "False"
    (string? x) (str-repr x)
    (integer? x) (str x)
    (float? x) (float-repr x)
    (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (py-repr k) ": " (py-repr v))) x)) "}")
    (sequential? x) (str "[" (str/join ", " (map py-repr x)) "]")
    (set? x) (if (empty? x) "set()" (str "{" (str/join ", " (map py-repr x)) "}"))
    :else (str x)))

;; ---------------------------------------------------------------------------
;; Formatting

(defn format-fixed
  "Python `format(x, '.Nf')`: exact binary value rounded half-to-even.
   bool formats as int; str/None raise like Python."
  [x digits]
  (cond
    (boolean? x) (format-fixed (if x 1 0) digits)
    (or (integer? x) (float? x))
    (let [d (double x)]
      (cond
        (Double/isNaN d) "nan"
        (Double/isInfinite d) (if (pos? d) "inf" "-inf")
        :else (let [s (.toPlainString (.setScale (if (integer? x)
                                                   (BigDecimal. (str x))
                                                   (BigDecimal. d))
                                                 (int digits)
                                                 RoundingMode/HALF_EVEN))]
                ;; Python keeps the sign of negative zero after rounding.
                (if (and (neg? (Math/copySign 1.0 d))
                         (not (str/starts-with? s "-")))
                  (str "-" s)
                  s))))
    (string? x) (raise "ValueError" "Unknown format code 'f' for object of type 'str'")
    (nil? x) (raise "TypeError" "unsupported format string passed to NoneType.__format__")
    :else (raise "TypeError" (str "unsupported format string passed to "
                                  (type-name x) ".__format__"))))

(defn py-round
  "Python `round(x, n)` for ints and floats."
  [x n]
  (pj/py-round x n))

(defn title
  "Python `str.title()`."
  [^String s]
  (let [sb (StringBuilder.)]
    (loop [prev-cased? false
           [cp & more] (iterator-seq (.iterator (.codePoints s)))]
      (if (nil? cp)
        (.toString sb)
        (let [cp (int cp)
              cased? (or (Character/isUpperCase cp) (Character/isLowerCase cp)
                         (Character/isTitleCase cp))]
          (.appendCodePoint sb (int (if cased?
                                      (if prev-cased?
                                        (Character/toLowerCase cp)
                                        (Character/toTitleCase cp))
                                      cp)))
          (recur cased? more))))))

(defn capitalize
  "Python `str.capitalize()`."
  [^String s]
  (if (empty? s) s (str (str/upper-case (subs s 0 1)) (str/lower-case (subs s 1)))))

(defn zfill [^String s width]
  (let [pad (- width (count s))]
    (if (pos? pad)
      (if (and (seq s) (#{\+ \-} (first s)))
        (str (first s) (apply str (repeat pad \0)) (subs s 1))
        (str (apply str (repeat pad \0)) s))
      s)))

(defn truthy?
  "Python truthiness."
  [x]
  (cond
    (nil? x) false
    (boolean? x) x
    (number? x) (not (zero? x))
    (string? x) (not (empty? x))
    (coll? x) (not (empty? x))
    :else true))

(defn py-float
  "Python `float(x)`."
  [x]
  (cond
    (boolean? x) (if x 1.0 0.0)
    (number? x) (double x)
    (string? x) (let [t (str/trim x)]
                  (try
                    (cond
                      (re-matches #"(?i)[+-]?(inf|infinity)" t)
                      (if (str/starts-with? t "-") Double/NEGATIVE_INFINITY Double/POSITIVE_INFINITY)
                      (re-matches #"(?i)[+-]?nan" t) Double/NaN
                      (re-matches #"[+-]?(\d[\d_]*\.?[\d_]*|\.\d[\d_]*)([eE][+-]?\d+)?" t)
                      (Double/parseDouble (str/replace t "_" ""))
                      :else (throw (NumberFormatException.)))
                    (catch NumberFormatException _
                      (raise "ValueError" (str "could not convert string to float: " (str-repr x))))))
    (nil? x) (raise "TypeError" "float() argument must be a string or a real number, not 'NoneType'")
    :else (raise "TypeError" (str "float() argument must be a string or a real number, not '"
                                  (type-name x) "'"))))

(defn py-int
  "Python `int(x)` (truncation for floats)."
  [x]
  (cond
    (boolean? x) (if x 1 0)
    (integer? x) x
    (float? x) (cond
                 (Double/isNaN x) (raise "ValueError" "cannot convert float NaN to integer")
                 (Double/isInfinite x) (raise "OverflowError" "cannot convert float infinity to integer")
                 :else (let [b (.toBigInteger (BigDecimal. (double x)))]
                         (if (< (.bitLength b) 64) (.longValue b) (bigint b))))
    (string? x) (let [t (str/replace (str/trim x) "_" "")]
                  (if (re-matches #"[+-]?\d+" t)
                    (let [b (biginteger t)] (if (< (.bitLength b) 64) (.longValue b) (bigint b)))
                    (raise "ValueError" (str "invalid literal for int() with base 10: " (str-repr x)))))
    :else (raise "TypeError" (str "int() argument must be a string, a bytes-like object or a real number, not '"
                                  (type-name x) "'"))))

;; ---------------------------------------------------------------------------
;; Comparison (Python semantics, raising TypeError on unorderable types)

(defn- num? [x] (and (number? x) (not (boolean? x))))

(defn- numeric [x] (if (boolean? x) (if x 1 0) x))

(defn py-eq [a b]
  (cond
    (and (or (number? a) (boolean? a)) (or (number? b) (boolean? b)))
    (let [a (numeric a) b (numeric b)]
      (if (or (float? a) (float? b))
        (== (double a) (double b))
        (== a b)))
    (and (map? a) (map? b)) (and (= (count a) (count b))
                                 (every? (fn [[k v]] (and (contains? b k) (py-eq v (get b k)))) a))
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b)) (every? true? (map py-eq a b)))
    :else (= a b)))

(defn py-compare
  "Python `<`-style ordering comparison, raising TypeError like Python."
  [op a b]
  (let [sym ({:lt "<" :le "<=" :gt ">" :ge ">="} op)
        c (cond
            (and (or (num? a) (boolean? a)) (or (num? b) (boolean? b)))
            (compare (double (numeric a)) (double (numeric b)))
            (and (string? a) (string? b)) (compare a b)
            :else (raise "TypeError" (str "'" sym "' not supported between instances of '"
                                          (type-name a) "' and '" (type-name b) "'")))]
    (case op :lt (neg? c) :le (<= c 0) :gt (pos? c) :ge (>= c 0))))

(defn py-in
  "Python `a in b`."
  [a b]
  (cond
    (string? b) (if (string? a)
                  (str/includes? b a)
                  (raise "TypeError" (str "'in <string>' requires string as left operand, not "
                                          (type-name a))))
    (map? b) (contains? b a)
    (or (sequential? b) (set? b)) (boolean (some #(py-eq a %) b))
    :else (raise "TypeError" (str "argument of type '" (type-name b) "' is not iterable"))))

;; ---------------------------------------------------------------------------
;; json.loads (CPython 3.12 decoder, messages included)

(defn- decode-error [msg ^String s pos]
  (let [pos (int pos)
        lineno (inc (count (filter #(= \newline %) (subs s 0 (min pos (count s))))))
        last-nl (.lastIndexOf s "\n" (int (dec pos)))
        colno (- pos last-nl)]
    (raise "JSONDecodeError" (str msg ": line " lineno " column " colno " (char " pos ")"))))

(defn- ws-end ^long [^String s ^long i]
  (loop [i i]
    (if (and (< i (count s)) (#{\space \tab \newline \return} (.charAt s i)))
      (recur (inc i))
      i)))

(def ^:private escapes
  {\" "\"" \\ "\\" \/ "/" \b "\b" \f "\f" \n "\n" \r "\r" \t "\t"})

(defn- decode-u
  "Decode the \\uXXXX escape whose backslash is at `pos`. CPython's C
   scanner reports a malformed escape at the `u`, one past the backslash."
  [^String s ^long pos]
  (let [esc (subs s (min (count s) (+ pos 2)) (min (count s) (+ pos 6)))]
    (if (re-matches #"[0-9a-fA-F]{4}" esc)
      (Integer/parseInt esc 16)
      (decode-error "Invalid \\uXXXX escape" s (inc pos)))))

(defn- scan-string
  "Returns [string end]; `end` is the index after the opening quote."
  [^String s ^long end]
  (let [begin (dec end) sb (StringBuilder.) n (count s)]
    (loop [i end]
      (let [j (loop [j i] (if (and (< j n) (let [c (.charAt s j)]
                                              (not (or (= c \") (= c \\) (< (int c) 0x20)))))
                            (recur (inc j)) j))]
        (when (>= j n) (decode-error "Unterminated string starting at" s begin))
        (.append sb (subs s i j))
        (let [c (.charAt s j)]
          (cond
            (= c \") [(.toString sb) (inc j)]
            (not= c \\) (decode-error "Invalid control character at" s j)
            :else
            (let [k (inc j)]
              (when (>= k n) (decode-error "Unterminated string starting at" s begin))
              (let [e (.charAt s k)]
                (if (not= e \u)
                  (if-let [r (escapes e)]
                    (do (.append sb r) (recur (inc k)))
                    (decode-error "Invalid \\escape" s j))
                  (let [uni (decode-u s j)
                        [uni next]
                        (if (and (<= 0xd800 uni 0xdbff)
                                 (= "\\u" (subs s (min n (+ j 6)) (min n (+ j 8)))))
                          (let [uni2 (decode-u s (+ j 6))]
                            (if (<= 0xdc00 uni2 0xdfff)
                              [(+ 0x10000 (bit-or (bit-shift-left (- uni 0xd800) 10)
                                                  (- uni2 0xdc00)))
                               (+ j 12)]
                              [uni (+ j 6)]))
                          [uni (+ j 6)])]
                    (.appendCodePoint sb (int uni))
                    (recur next)))))))))))

(def ^:private number-pattern #"(-?(?:0|[1-9]\d*))(\.\d+)?([eE][-+]?\d+)?")

(declare scan-once)

(defn- assoc-ordered [m k v]
  ;; Python dict: a duplicate key keeps its first position, last value wins.
  (if (contains? m k)
    (apply array-map (mapcat (fn [[k' v']] [k' (if (= k' k) v v')]) m))
    (apply array-map (concat (mapcat identity m) [k v]))))

(defn- scan-object [^String s ^long end opts]
  (let [n (count s)
        end (if (and (< end n) (not= \" (.charAt s end))) (ws-end s end) end)]
    (cond
      (and (< end n) (= \} (.charAt s end))) [(array-map) (inc end)]
      (not (and (< end n) (= \" (.charAt s end))))
      (decode-error "Expecting property name enclosed in double quotes" s end)
      :else
      (loop [pairs (array-map) end (inc end)]
        (let [[k end] (scan-string s end)
              end (if (and (< end n) (= \: (.charAt s end)))
                    end
                    (let [e (ws-end s end)]
                      (if (and (< e n) (= \: (.charAt s e)))
                        e
                        (decode-error "Expecting ':' delimiter" s e))))
              end (ws-end s (inc end))
              [v end] (scan-once s end opts)
              pairs (assoc-ordered pairs k v)
              end (ws-end s end)
              c (when (< end n) (.charAt s end))
              end (inc end)]
          (cond
            (= c \}) [pairs end]
            (not= c \,) (decode-error "Expecting ',' delimiter" s (dec end))
            :else
            (let [end (ws-end s end)
                  c (when (< end n) (.charAt s end))
                  end (inc end)]
              (if (not= c \")
                (decode-error "Expecting property name enclosed in double quotes" s (dec end))
                (recur pairs end)))))))))

(defn- scan-array [^String s ^long end opts]
  (let [n (count s)
        end (ws-end s end)]
    (if (and (< end n) (= \] (.charAt s end)))
      [[] (inc end)]
      (loop [values [] end end]
        (let [[v end] (scan-once s end opts)
              values (conj values v)
              end (ws-end s end)
              c (when (< end n) (.charAt s end))
              end (inc end)]
          (cond
            (= c \]) [values end]
            (not= c \,) (decode-error "Expecting ',' delimiter" s (dec end))
            :else (recur values (ws-end s end))))))))

(defn- scan-once [^String s ^long idx {:keys [parse-int] :as opts}]
  (let [n (count s)
        starts? #(and (<= (+ idx (count %)) n) (= % (subs s idx (+ idx (count %)))))]
    (when (>= idx n) (decode-error "Expecting value" s idx))
    (let [c (.charAt s idx)]
      (cond
        (= c \") (scan-string s (inc idx))
        (= c \{) (scan-object s (inc idx) opts)
        (= c \[) (scan-array s (inc idx) opts)
        (starts? "null") [nil (+ idx 4)]
        (starts? "true") [true (+ idx 4)]
        (starts? "false") [false (+ idx 5)]
        :else
        (let [m (re-matcher number-pattern s)]
          (if (and (.find m (int idx)) (= idx (.start m)))
            (let [[_ integer frac exp] (re-groups m)
                  end (.end m)]
              (if (or frac exp)
                [(Double/parseDouble (str integer frac exp)) end]
                [(if (= parse-int :float)
                   (Double/parseDouble integer)
                   (let [b (biginteger integer)]
                     (if (< (.bitLength b) 64) (.longValue b) (bigint b))))
                 end]))
            (cond
              (starts? "NaN") [Double/NaN (+ idx 3)]
              (starts? "Infinity") [Double/POSITIVE_INFINITY (+ idx 8)]
              (starts? "-Infinity") [Double/NEGATIVE_INFINITY (+ idx 9)]
              :else (decode-error "Expecting value" s idx))))))))

(defn json-loads
  "Python `json.loads(s)`; `{:parse-int :float}` mirrors `parse_int=float`.
   Objects become insertion-ordered maps with string keys."
  ([s] (json-loads s {}))
  ([s opts]
   (when-not (string? s)
     (raise "TypeError" (str "the JSON object must be str, bytes or bytearray, not "
                             (type-name s))))
   (let [^String s s]
     (when (str/starts-with? s "﻿")
       (decode-error "Unexpected UTF-8 BOM (decode using utf-8-sig)" s 0))
     (let [[v end] (scan-once s (ws-end s 0) opts)
           end (ws-end s end)]
       (when (not= end (count s)) (decode-error "Extra data" s end))
       v))))

;; ---------------------------------------------------------------------------
;; json.dumps

(declare dumps-indent)

(defn- dumps-indent* [x level ^StringBuilder sb]
  (let [pad (fn [l] (apply str "\n" (repeat (* 2 l) \space)))]
    (cond
      (and (map? x) (seq x))
      (do (.append sb "{")
          (loop [[[k v] & more] (seq x) first? true]
            (when (some? k)
              (when-not first? (.append sb ","))
              (.append sb (pad (inc level)))
              (.append sb (pj/dumps (if (keyword? k) (name k) (str k))))
              (.append sb ": ")
              (dumps-indent* v (inc level) sb)
              (recur more false)))
          (.append sb (pad level))
          (.append sb "}"))
      (and (sequential? x) (seq x))
      (do (.append sb "[")
          (loop [[v & more :as xs] (seq x) first? true]
            (when xs
              (when-not first? (.append sb ","))
              (.append sb (pad (inc level)))
              (dumps-indent* v (inc level) sb)
              (recur more false)))
          (.append sb (pad level))
          (.append sb "]"))
      (float? x) (.append sb (cond (Double/isNaN x) "NaN"
                                   (Double/isInfinite x) (if (pos? x) "Infinity" "-Infinity")
                                   :else (pj/py-float-repr x)))
      :else (.append sb (pj/dumps x)))))

(defn dumps-indent
  "Python `json.dumps(x, indent=2)`."
  [x]
  (let [sb (StringBuilder.)]
    (dumps-indent* x 0 sb)
    (.toString sb)))
