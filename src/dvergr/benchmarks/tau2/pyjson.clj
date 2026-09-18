(ns dvergr.benchmarks.tau2.pyjson
  "Python-compatible JSON for transcribed tau2 environments.

   tau2 grades by hashing `json.dumps(db, sort_keys=True)` and shows agents
   `json.dumps(model_dump())` tool results. Byte equality with the upstream
   environment therefore needs Python's float repr, `round`, default
   separators, and `ensure_ascii` escaping. Objects are ordered maps whose
   insertion order mirrors pydantic field declaration order."
  (:import [com.fasterxml.jackson.databind ObjectMapper JsonNode]
           [java.math BigDecimal RoundingMode]))

;; ---------------------------------------------------------------------------
;; Numbers

(defn py-float-repr
  "Python `repr(float)`: shortest round-tripping digits (JDK 19+ Double.toString
   is shortest too), fixed notation for decimal exponents in [-4, 16),
   scientific `1e+16` style otherwise."
  [^double x]
  (cond
    (Double/isNaN x) "NaN"
    (Double/isInfinite x) (if (pos? x) "Infinity" "-Infinity")
    (zero? x) (if (neg? (Math/copySign 1.0 x)) "-0.0" "0.0")
    :else
    (let [bd (BigDecimal. (Double/toString x))
          ;; Double.toString renders at least two significant digits, e.g.
          ;; 4.9E-324 where Python's shortest repr is 5e-324.
          one-digit (.round bd (java.math.MathContext. 1 RoundingMode/HALF_EVEN))
          bd (if (= x (.doubleValue one-digit)) one-digit bd)
          neg (neg? (.signum bd))
          unscaled (.toString (.abs (.unscaledValue (.stripTrailingZeros bd))))
          ;; value = 0.d1d2... * 10^(exp+1); exp is the decimal exponent of d1
          exp (- (dec (count unscaled)) (.scale (.stripTrailingZeros bd)))
          digits unscaled
          n (count digits)
          body (if (and (>= exp -4) (< exp 16))
                 (cond
                   (neg? exp) (str "0." (apply str (repeat (dec (- exp)) \0)) digits)
                   (>= exp (dec n)) (str digits (apply str (repeat (- exp (dec n)) \0)) ".0")
                   :else (str (subs digits 0 (inc exp)) "." (subs digits (inc exp))))
                 (str (subs digits 0 1)
                      (when (> n 1) (str "." (subs digits 1)))
                      "e" (if (neg? exp) "-" "+")
                      (let [e (Math/abs (long exp))]
                        (if (< e 10) (str "0" e) (str e)))))]
      (if neg (str "-" body) body))))

(defn py-round
  "Python `round(x, ndigits)`. For floats Python rounds the exact binary value
   half-to-even; integers are returned unchanged."
  [x ndigits]
  (if (integer? x)
    x
    (let [d (double x)
          r (-> (BigDecimal. d)
                (.setScale (int ndigits) RoundingMode/HALF_EVEN)
                (.doubleValue))]
      ;; BigDecimal has no negative zero; Python keeps the sign:
      ;; round(-2e-05, 4) == -0.0.
      (if (zero? r) (Math/copySign 0.0 d) r))))

(defn py-str
  "Python `str()` for the scalar values tau2 tools stringify."
  [x]
  (cond
    (nil? x) "None"
    (true? x) "True"
    (false? x) "False"
    (float? x) (py-float-repr x)
    :else (str x)))

;; ---------------------------------------------------------------------------
;; Encoding

(defn- escape-string [^String s ^StringBuilder sb]
  (.append sb \")
  (dotimes [i (.length s)]
    (let [c (.charAt s i)]
      (case c
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed (.append sb "\\f")
        (if (or (< (int c) 0x20) (> (int c) 0x7e))
          ;; ensure_ascii: UTF-16 code units, which is exactly Python's
          ;; surrogate-pair escaping for astral characters.
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c)))))
  (.append sb \"))

(defn- write-value [x sort-keys? ^StringBuilder sb]
  (cond
    (nil? x) (.append sb "null")
    (true? x) (.append sb "true")
    (false? x) (.append sb "false")
    (string? x) (escape-string x sb)
    (keyword? x) (escape-string (name x) sb)
    (float? x) (.append sb (py-float-repr x))
    (integer? x) (.append sb (str x))
    (map? x)
    (let [entries (if sort-keys?
                    (sort-by (comp str key) x)
                    (seq x))]
      (.append sb \{)
      (loop [[[k v] & more] entries first? true]
        (when (some? k)
          (when-not first? (.append sb ", "))
          (escape-string (if (keyword? k) (name k) (str k)) sb)
          (.append sb ": ")
          (write-value v sort-keys? sb)
          (recur more false)))
      (.append sb \}))
    (sequential? x)
    (do (.append sb \[)
        (loop [[v & more :as xs] (seq x) first? true]
          (when xs
            (when-not first? (.append sb ", "))
            (write-value v sort-keys? sb)
            (recur more false)))
        (.append sb \]))
    :else (escape-string (str x) sb)))

(defn dumps
  "Python `json.dumps(x, sort_keys=sort-keys?)` with default separators and
   `ensure_ascii=True`. Maps keep their iteration order unless sorted."
  ([x] (dumps x false))
  ([x sort-keys?]
   (let [sb (StringBuilder.)]
     (write-value x sort-keys? sb)
     (.toString sb))))

(defn sha256-hex [^String s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn dict-hash
  "tau2 `get_dict_hash`: sha256 of `json.dumps(obj, sort_keys=True)`."
  [x]
  (sha256-hex (dumps x true)))

;; ---------------------------------------------------------------------------
;; Decoding (order preserving)

(def ^:private ^ObjectMapper mapper (ObjectMapper.))

(defn- node->value [^JsonNode node]
  (cond
    (.isObject node)
    (apply array-map
           (mapcat (fn [^java.util.Map$Entry e]
                     [(.getKey e) (node->value (.getValue e))])
                   (iterator-seq (.fields node))))
    (.isArray node) (mapv node->value (iterator-seq (.elements node)))
    (.isTextual node) (.asText node)
    (.isBoolean node) (.asBoolean node)
    (.isNull node) nil
    (.isIntegralNumber node) (let [n (.bigIntegerValue node)]
                               (if (< (.bitLength n) 64) (.longValue n) (bigint n)))
    (.isFloatingPointNumber node) (.asDouble node)
    :else (throw (ex-info "Unsupported JSON node" {:node (str node)}))))

(defn parse
  "Parse JSON text into Clojure data with string keys and objects as
   insertion-ordered array maps. Floats stay doubles, integers stay longs."
  [^String s]
  (node->value (.readTree mapper s)))

(defn parse-file [path]
  (parse (slurp path)))

(comment
  (py-float-repr 242.1500000000001)
  (py-float-repr 1e16)
  (py-round 2.675 2)
  (dumps {"b" 1 "a" [1.5 nil true]} true))
