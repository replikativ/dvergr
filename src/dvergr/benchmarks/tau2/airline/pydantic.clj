(ns dvergr.benchmarks.tau2.airline.pydantic
  "The slice of pydantic 2.13 (lax mode) that tau2's airline tools expose.

   Airline tools build pydantic models from agent-supplied JSON
   (`FlightInfo(**d)`, `Passenger(**d)`, `Payment(**d)`, `Reservation(...)`,
   `Certificate(...)`), so malformed arguments surface to the agent as the
   text of a `ValidationError`. This namespace reproduces the coercions those
   models perform (int from bool/integral float/numeric string, float from
   numeric string, exact literals, lists, nested models) and the error text
   byte for byte, including pydantic's 50-character truncation of
   `input_value` reprs. Behavior was pinned against pydantic 2.13.5 /
   pydantic-core 2.46.5 through the oracle."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.python :as py]))

(def docs-version
  "Version segment of the error URLs (`https://errors.pydantic.dev/<v>/v/<type>`)."
  "2.13")

(defn- err [type msg input] {:loc [] :type type :msg msg :input input})

;; ---------------------------------------------------------------------------
;; Scalar validators: `(v x) -> [:ok value] | [:err [error ...]]`

(defn v-str [x]
  (if (string? x) [:ok x] [:err [(err "string_type" "Input should be a valid string" x)]]))

(def ^:private two-63 (Math/pow 2.0 63))

(defn v-int
  "pydantic `int` in lax mode."
  [x]
  (cond
    (boolean? x) [:ok (if x 1 0)]
    (integer? x) [:ok x]
    (float? x)
    (let [d (double x)]
      (cond
        (or (Double/isNaN d) (Double/isInfinite d))
        [:err [(err "finite_number" "Input should be a finite number" x)]]
        (not= d (Math/floor d))
        [:err [(err "int_from_float" "Input should be a valid integer, got a number with a fractional part" x)]]
        (>= (Math/abs d) two-63)
        [:err [(err "int_parsing_size" "Unable to parse input string as an integer, exceeded maximum size" x)]]
        :else [:ok (long d)]))
    (string? x)
    (let [t (str/trim x)]
      (if (re-matches #"[+-]?\d(?:_?\d)*(?:\.0+)?" t)
        [:ok (py/py-int (str/replace t #"\.0+$" ""))]
        [:err [(err "int_parsing" "Input should be a valid integer, unable to parse string as an integer" x)]]))
    :else [:err [(err "int_type" "Input should be a valid integer" x)]]))

(defn v-float
  "pydantic `float` in lax mode."
  [x]
  (cond
    (boolean? x) [:ok (if x 1.0 0.0)]
    (number? x) [:ok (double x)]
    (string? x)
    (try [:ok (py/py-float x)]
         (catch clojure.lang.ExceptionInfo _
           [:err [(err "float_parsing" "Input should be a valid number, unable to parse string as a number" x)]]))
    :else [:err [(err "float_type" "Input should be a valid number" x)]]))

(defn- or-list
  "pydantic's literal expectation text: `'a', 'b' or 'c'`."
  [values]
  (let [q (mapv py/py-repr values)]
    (if (= 1 (count q))
      (first q)
      (str (str/join ", " (butlast q)) " or " (last q)))))

(defn v-literal [& values]
  (let [allowed (set values)
        msg (str "Input should be " (or-list values))]
    (fn [x]
      (if (and (string? x) (contains? allowed x))
        [:ok x]
        [:err [(err "literal_error" msg x)]]))))

(defn v-optional [v] (fn [x] (if (nil? x) [:ok nil] (v x))))

(defn- prefix [loc-part errors] (mapv #(update % :loc (fn [l] (into [loc-part] l))) errors))

(defn v-list [item-v]
  (fn [x]
    (if-not (sequential? x)
      [:err [(err "list_type" "Input should be a valid list" x)]]
      (let [results (map-indexed (fn [i item] [i (item-v item)]) x)
            errors (vec (mapcat (fn [[i [tag e]]] (when (= :err tag) (prefix i e))) results))]
        (if (seq errors)
          [:err errors]
          [:ok (mapv (fn [[_ [_ v]]] v) results)])))))

;; ---------------------------------------------------------------------------
;; Models

(defn validate-fields
  "Validate a kwargs/dict `data` against `fields` `[[name validator] ...]`
   (declaration order; missing fields without defaults are `missing`).
   `defaults` maps optional field names to their default. Extra keys are
   ignored (pydantic's default `extra='ignore'`). Returns `[:ok ordered-map]`
   or `[:err errors]`."
  ([fields data] (validate-fields fields data {}))
  ([fields data defaults]
   (let [results (for [[k v] fields]
                   [k (cond
                        (contains? data k) (v (get data k))
                        (contains? defaults k) [:ok (get defaults k)]
                        :else [:err [(err "missing" "Field required" data)]])])
         errors (vec (mapcat (fn [[k [tag e]]] (when (= :err tag) (prefix k e))) results))]
     (if (seq errors)
       [:err errors]
       [:ok (apply array-map (mapcat (fn [[k [_ v]]] [k v]) results))]))))

(defn v-model
  "A nested model field: a dict is validated, anything else is `model_type`."
  [model-name fields & [defaults]]
  (fn [x]
    (if (map? x)
      (validate-fields fields x (or defaults {}))
      [:err [(err "model_type" (str "Input should be a valid dictionary or instance of " model-name) x)]])))

;; ---------------------------------------------------------------------------
;; ValidationError text

(defn- truncated-repr
  "pydantic-core shows `input_value` reprs longer than 50 characters as the
   first 25 + `...` + the last 24."
  [x]
  (let [r (py/py-repr x)
        cps (vec (.toArray (.codePoints ^String r)))
        n (count cps)
        s (fn [xs] (String. (int-array xs) 0 (count xs)))]
    (if (> n 50)
      (str (s (subvec cps 0 25)) "..." (s (subvec cps (- n 24))))
      r)))

(defn error-text [model-name errors]
  (let [n (count errors)]
    (str n " validation error" (when (not= 1 n) "s") " for " model-name "\n"
         (str/join "\n"
                   (for [{:keys [loc type msg input]} errors]
                     (str (str/join "." (map str loc)) "\n  " msg
                          " [type=" type ", input_value=" (truncated-repr input)
                          ", input_type=" (py/type-name input) "]\n"
                          "    For further information visit https://errors.pydantic.dev/"
                          docs-version "/v/" type))))))

(defn construct!
  "`Model(**data)`: the validated ordered map, or raise `ValidationError`."
  ([model-name fields data] (construct! model-name fields data {}))
  ([model-name fields data defaults]
   (let [[tag v] (validate-fields fields data defaults)]
     (if (= :ok tag)
       v
       (py/raise "ValidationError" (error-text model-name v))))))
