(ns dvergr.benchmarks.bfcl.core
  "Berkeley Function Calling Leaderboard (BFCL v4), the single-turn Python
   categories, transcribed: pinned data, tool compilation, and the AST checker.

   A BFCL task is one question and a set of function docs; the answer is the
   function call(s) the model emits in its FIRST response. Nothing is executed.
   The grade is structural: `ast-checker` compares the emitted calls with the
   task's possible answers (per parameter, a list of accepted values; `\"\"`
   marks an optional parameter). Irrelevance tasks expect no call at all,
   relevance tasks at least one.

   The checker is a transcription of upstream's `ast_checker.py`, Python
   semantics included (exact `type(x) ==` checks, `bool` is not `int`, `1 ==
   1.0 == True` under `in`), and quirks included: a verdict must be the one
   upstream would give. `dvergr.benchmarks.bfcl.equivalence` proves that
   against upstream's own code. Error MESSAGES are reproduced where they are
   cheap; the contract is `:valid` and `:error-type`.

   Not here: Java and JavaScript categories (string-encoded literals with their
   own converters), multi-turn (stateful API simulations), agentic (memory,
   web search)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.python :as py]))

;; ---------------------------------------------------------------------------
;; Provenance and data

(def upstream
  {:repository "https://github.com/ShishirPatil/gorilla"
   :path "berkeley-function-call-leaderboard"
   :revision "6ea57973c7a6097fd7c5915698c54c17c5b1b6c8"
   :version "v4"
   :license "Apache-2.0"})

(def default-root
  "A gorilla checkout next to Dvergr. Override with `:root`."
  "../gorilla")

(def categories
  "Category -> how it is graded and the pinned digests of its files
   `[questions possible-answers]`."
  {"simple_python"          {:kind :ast :checker :simple :live? false
                             :digests ["82dd63ba502eb2520c6b5d1d9a5c4b590e03ff261565175561f6228a367d1991"
                                       "90cd5bc653690ee8e459b5b3f3fc9458606f7f3fcbf795bb51b7dc581f8c86dc"]}
   "multiple"               {:kind :ast :checker :multiple :live? false
                             :digests ["aef168155ebd74b7ac2401198b201343bc7d16d7a3d7e0d4e6d8ee82c6969b2a"
                                       "244e00ce9395df948bcafc7bee64e8f9c87ef70887587d83cae45b13699f3047"]}
   "parallel"               {:kind :ast :checker :parallel :live? false
                             :digests ["19f51a82eff42e5d62541aa500115a056eb78f437c2ba1f10415fd7c8e5dda84"
                                       "8a6aa19c1adddc6a5a2f7e40f9dbf30cc7e95815e7b830c90589ab318229e0f0"]}
   "parallel_multiple"      {:kind :ast :checker :parallel :live? false
                             :digests ["8863ea8433239f55c5f016154cf0830853c89f693c6ea270396a2fa121960579"
                                       "5ebf24f458c1f16300c05505d83d6f0a1b68b79be273a033febd0d4f840507e3"]}
   "irrelevance"            {:kind :irrelevance :live? false
                             :digests ["2b6ed4c2e992cdcf5f1678a701851f944bef7550ee026ed1ddb89efed5be01a6" nil]}
   "live_simple"            {:kind :ast :checker :simple :live? true
                             :digests ["1af2ac87dca47556db7b7e37e51e28b459a38b594e3c7b3c792b4903598ca0c4"
                                       "fec9cfa9744a936f9126981e85a2023da1e63e273eafebc81923a1162fad70ce"]}
   "live_multiple"          {:kind :ast :checker :multiple :live? true
                             :digests ["fd8ccfad4d911420d0e3341dbe2fff77d1d341da934248b9bb2bda24ab3a10c8"
                                       "97e90d59c5bd76c55a2920ce93e5566e9046307d3f558578f085f9d3a56c3084"]}
   "live_parallel"          {:kind :ast :checker :parallel :live? true
                             :digests ["6c26e9fdc3350cf596e6d1ea9c179cbff834761bccf562f4141ed29a839ca421"
                                       "8a9f189ff0e832ebbbbdade1fd95a7dbcc67406e9177df3f0aad76f59ab00350"]}
   "live_parallel_multiple" {:kind :ast :checker :parallel :live? true
                             :digests ["21d4b9319c1faac431e22757b367ea28917fe467364c3a4b17f16ec06d4f6e79"
                                       "f5b5f360556c5feb51db46fb9f56ee4b304f4b45b161599bbb14161c98a2873f"]}
   "live_irrelevance"       {:kind :irrelevance :live? true
                             :digests ["6559fda2beaceb609a2cd2e504c65b4a56cb448e1ef88fddfd199e163d163349" nil]}
   "live_relevance"         {:kind :relevance :live? true
                             :digests ["e03f9e241657a137cba48a89ee12f47bf3fcb7e4f6274263e9c699a0c974203a" nil]}})

(defn- data-file [root & parts]
  (apply io/file root (:path upstream) "bfcl_eval" "data" parts))

(defn- verified-lines [file expected-digest]
  (let [text (slurp file)
        digest (pj/sha256-hex text)]
    (when (not= digest expected-digest)
      (throw (ex-info "BFCL data file does not match the pinned upstream revision"
                      {:type ::data-digest-mismatch :path (str file)
                       :expected expected-digest :actual digest :upstream upstream})))
    (into [] (comp (remove str/blank?) (map pj/parse)) (str/split-lines text))))

(defn load-category
  "The tasks of `category`, in upstream order:
   `{:id :category :question :functions :ground-truth}`. `:ground-truth` is
   private data: it never reaches a candidate."
  ([category] (load-category category nil))
  ([category {:keys [root] :or {root default-root}}]
   (let [{[question-digest answer-digest] :digests :as spec}
         (or (get categories category)
             (throw (ex-info "Unknown BFCL category" {:type ::unknown-category :category category})))
         file (str "BFCL_v4_" category ".json")
         answers (when answer-digest
                   (into {} (map (juxt #(get % "id") #(get % "ground_truth")))
                         (verified-lines (data-file root "possible_answer" file) answer-digest)))]
     (mapv (fn [entry]
             (cond-> {:id (get entry "id")
                      :category category
                      :kind (:kind spec)
                      :question (get entry "question")
                      :functions (get entry "function")}
               answers (assoc :ground-truth
                              (or (get answers (get entry "id"))
                                  (throw (ex-info "BFCL task without possible answers"
                                                  {:type ::missing-answer :id (get entry "id")}))))))
           (verified-lines (data-file root file) question-digest)))))

(def unsatisfiable
  "Tasks of the pinned revision that NO answer can pass: upstream data faults.
   Found by building an accepted answer for every task from its own possible
   answers (`equivalence/gold-calls`); the test suite pins that these and only
   these fail. They bound the accuracy anybody can reach, so a report should
   say whether they are counted.

     live_multiple_862-181-3  an accepted value for a parameter the function
                              does not declare: with it, unexpected parameter;
                              without it, missing parameter
     live_multiple_964-207-0  a required parameter the possible answers do not
                              have: with it, unexpected; without it, missing
     live_simple_106-63-0     required array parameters with an EMPTY list of
     live_simple_112-68-0     accepted values: no list passes the nested type
                              check, and leaving them out is missing"
  #{"live_multiple_862-181-3" "live_multiple_964-207-0"
    "live_simple_106-63-0" "live_simple_112-68-0"})

;; ---------------------------------------------------------------------------
;; What the candidate sees: function docs compiled to tools
;;
;; Upstream `_func_doc_language_specific_pre_processing` (Python branch) and
;; `convert_to_tool(functions, GORILLA_TO_OPENAPI, ModelStyle.ANTHROPIC)`.

(def ^:private python-hint " Note that the provided function is in Python 3 syntax.")

(def ^:private gorilla->openapi
  {"integer" "integer" "number" "number" "float" "number" "string" "string"
   "boolean" "boolean" "bool" "boolean" "array" "array" "list" "array"
   "dict" "object" "object" "object" "tuple" "array" "any" "string"
   "byte" "integer" "short" "integer" "long" "integer" "double" "number"
   "char" "string" "ArrayList" "array" "Array" "array" "HashMap" "object"
   "Hashtable" "object" "Queue" "array" "Stack" "array" "Any" "string"
   "String" "string" "Bigint" "integer"})

(defn- mapped-type!
  "`mapping[t]` where upstream indexes without a default (a KeyError there)."
  [t]
  (or (gorilla->openapi t)
      (py/raise "KeyError" (py/py-repr t))))

(declare cast-properties)

(defn- cast-property [value]
  (let [value (if-not (contains? value "type")
                (assoc value "type" "string")
                (let [t (get value "type")]
                  (cond-> value
                    (= "float" t) (-> (assoc "format" "float")
                                      (assoc "description"
                                             (str (or (get value "description")
                                                      (py/raise "KeyError" "'description'"))
                                                  " This is a float type value.")))
                    true (assoc "type" (get gorilla->openapi t "string")))))]
    (if-not (#{"array" "object"} (get value "type"))
      value
      (cond
        (contains? value "properties")
        (update value "properties" cast-properties)

        (contains? value "items")
        (let [items (get value "items")
              items (assoc items "type" (mapped-type! (get items "type")))
              items (cond
                      (and (= "array" (get items "type")) (contains? items "items"))
                      (update-in items ["items" "type"] mapped-type!)

                      (and (= "object" (get items "type")) (contains? items "properties"))
                      (update items "properties" cast-properties)

                      :else items)]
          (assoc value "items" items))

        :else value))))

(defn- cast-properties [properties]
  (reduce-kv (fn [m k v] (assoc m k (cast-property v))) properties properties))

(defn tool-name
  "The name a provider sees: `.` is not allowed in tool names, upstream
   replaces it by `_` (`underscore_to_dot`)."
  [function-name]
  (str/replace function-name "." "_"))

(defn compile-tools
  "`functions` (a task's function docs) as provider tool specs:
   `[{\"name\" \"description\" \"input_schema\"}]`."
  [functions]
  (mapv (fn [function]
          (let [parameters (-> (get function "parameters")
                               (assoc "type" "object")
                               (update "properties" cast-properties))]
            (-> function
                (assoc "name" (tool-name (get function "name")))
                (update "description" str python-hint)
                (dissoc "parameters")
                (assoc "input_schema" parameters))))
        functions))

;; ---------------------------------------------------------------------------
;; The AST checker (upstream `eval_checker/ast_eval/ast_checker.py`)

(def ^:private python-type
  "PYTHON_TYPE_MAPPING, as `type(x).__name__`."
  {"string" "str" "integer" "int" "float" "float" "boolean" "bool"
   "array" "list" "tuple" "list" "dict" "dict" "any" "str"})

(def ^:private nested-types #{"array" "tuple"})

(defn- type-class-str
  "Python `str(<class 'int'>)`."
  [type-name]
  (str "<class '" type-name "'>"))

(defn- possible-answer-type
  "`get_possible_answer_type`: the type of the first answer that is not the
   optional marker."
  [possible-answer]
  (some (fn [answer] (when-not (py/py-eq answer "") (py/type-name answer)))
        possible-answer))

(defn- standardize-string [^String s]
  (-> (str/replace s #"[ \,\.\/\-\_\*\^]" "")
      (.toLowerCase java.util.Locale/ROOT)
      (str/replace "'" "\"")))

(defn- standardize [x]
  (if (string? x) (standardize-string x) x))

(defn- type-checker
  [param value possible-answer expected-description expected-type nested-type]
  (let [answer-type (possible-answer-type possible-answer)
        variable? (boolean (and answer-type (not= answer-type expected-type)))
        nested-failure
        (when (= (py/type-name value) expected-type)
          (if (nil? nested-type)
            ::simple-match
            (if (some (fn [answer-item]
                        (or (not (sequential? answer-item))
                            (every? (fn [value-item]
                                      (:valid (type-checker param value-item answer-item
                                                            (type-class-str nested-type)
                                                            nested-type nil)))
                                    value)))
                      possible-answer)
              ::nested-match
              {:valid false
               :error [(str "Nested type checking failed for parameter " (py/py-repr param)
                            ". Expected outer type " expected-description
                            " with inner type " (type-class-str nested-type)
                            ". Parameter value: " (py/py-repr value) ".")]
               :error-type "type_error:nested"})))]
    (cond
      (= ::simple-match nested-failure)
      {:valid true :error [] :variable? variable? :error-type "type_error:simple"}

      (= ::nested-match nested-failure)
      {:valid true :error [] :variable? variable?}

      ;; Upstream falls through after a nested failure: a value whose type is
      ;; the type of the possible answers is returned as "a variable", with
      ;; the nested failure still in the result.
      (and answer-type (= (py/type-name value) answer-type))
      (if nested-failure
        (assoc nested-failure :variable? true)
        {:valid true :error [] :variable? true :error-type "type_error:simple"})

      :else
      {:valid false
       :error (conj (vec (:error nested-failure))
                    (str "Incorrect type for parameter " (py/py-repr param)
                         ". Expected type " expected-description
                         ", got " (py/type-name value)
                         ". Parameter value: " (py/py-repr value) "."))
       :variable? false
       :error-type "type_error:simple"})))

(defn- string-checker [param model-output possible-answer]
  (let [answers (into [] (comp (filter string?) (map standardize-string)) possible-answer)]
    (if (py/py-in (standardize-string model-output) answers)
      {:valid true :error []}
      {:valid false
       :error [(str "Invalid value for parameter " (py/py-repr param) ": " (py/py-repr model-output)
                    ". Expected one of " (py/py-repr possible-answer) ". Case insensitive.")]
       :error-type "value_error:string"})))

(defn- iterable!
  "What upstream iterates with `range(len(x))` and indexes: a list or a string."
  [x]
  (cond (sequential? x) x
        (string? x) (map str x)
        :else (py/raise "TypeError" (str "object of type '" (py/type-name x) "' has no len()"))))

(defn- list-checker [param model-output possible-answer]
  (let [output (mapv standardize model-output)
        answers (mapv (fn [answer] (mapv standardize (iterable! answer))) possible-answer)]
    (if (py/py-in output answers)
      {:valid true :error []}
      {:valid false
       :error [(str "Invalid value for parameter " (py/py-repr param) ": " (py/py-repr model-output)
                    ". Expected one of " (py/py-repr possible-answer) ".")]
       :error-type "value_error:list/tuple"})))

(defn- dict-checker [_param model-output possible-answers]
  (loop [answers (seq possible-answers)
         result {:valid false :error [] :error-type "dict_checker:unclear"}]
    (if-not answers
      result
      (let [answer (first answers)]
        (if (py/py-eq answer "")
          (recur (next answers) result)
          (let [_ (when-not (map? answer)
                    (py/raise "TypeError" "possible answer of a dict parameter is not a dict"))
                unexpected
                (some (fn [[k v]]
                        (cond
                          (not (contains? answer k))
                          {:valid false :error [(str "Unexpected dict key parameter: '" k "'.")]
                           :error-type "value_error:dict_key"}

                          (not (py/py-in (standardize v)
                                         (mapv standardize (iterable! (get answer k)))))
                          {:valid false
                           :error [(str "Invalid value for parameter " (py/py-repr k) ": " (py/py-repr v)
                                        ". Expected one of "
                                        (py/py-repr (mapv standardize (iterable! (get answer k)))) ".")]
                           :error-type "value_error:dict_value"}))
                      model-output)
                ;; Upstream runs this second loop even after the first one
                ;; failed, and its error type wins.
                missing
                (some (fn [[k v]]
                        (when (and (not (contains? model-output k))
                                   (not (py/py-in "" v)))
                          {:valid false :error [(str "Missing dict key parameter: '" k "'.")]
                           :error-type "value_error:dict_key"}))
                      answer)
                failure (cond
                          (and unexpected missing)
                          (assoc missing :error (into (:error unexpected) (:error missing)))
                          :else (or unexpected missing))]
            (if failure
              (recur (next answers) failure)
              {:valid true :error []})))))))

(defn- list-dict-checker [param model-output possible-answers]
  (loop [answers (seq possible-answers)
         result {:valid false :error [] :error-type "list_dict_checker:unclear"}]
    (if-not answers
      result
      (let [answer (iterable! (first answers))]
        (if (not= (count model-output) (count answer))
          (recur (next answers)
                 {:valid false :error ["Wrong number of dictionaries in the list."]
                  :error-type "value_error:list_dict_count"})
          (let [failure (some (fn [[output-dict answer-dict]]
                                (let [r (dict-checker param output-dict [answer-dict])]
                                  (when-not (:valid r) r)))
                              (map vector model-output answer))]
            (if failure
              (recur (next answers) failure)
              {:valid true :error []})))))))

(defn- simple-function-checker
  [function model-output possible-answer]
  (let [_ (when (nil? function)
            ;; `find_description` found nothing and upstream subscripts None
            (py/raise "TypeError" "'NoneType' object is not subscriptable"))
        possible-answer (val (first possible-answer))
        function-name (tool-name (get function "name"))
        param-details (get-in function ["parameters" "properties"])
        required (if (contains? (get function "parameters") "required")
                   (get-in function ["parameters" "required"])
                   (py/raise "KeyError" "'required'"))]
    (if-not (contains? model-output function-name)
      {:valid false
       :error [(str "Function name " (py/py-repr function-name) " not found in model output.")]
       :error-type "simple_function_checker:wrong_func_name"}
      (let [model-params (get model-output function-name)]
        (or
         (some (fn [param]
                 (when-not (contains? model-params param)
                   {:valid false :error [(str "Missing required parameter: " (py/py-repr param) ".")]
                    :error-type "simple_function_checker:missing_required"}))
               required)
         (some
          (fn [[param value]]
            (if (or (not (contains? param-details param)) (not (contains? possible-answer param)))
              {:valid false :error [(str "Unexpected parameter: " (py/py-repr param) ".")]
               :error-type "simple_function_checker:unexpected_param"}
              (let [details (get param-details param)
                    description (get details "type")
                    expected (or (python-type description) (py/raise "KeyError" (py/py-repr description)))
                    nested (when (nested-types description)
                             (let [nested-description (get-in details ["items" "type"])]
                               (or (python-type nested-description)
                                   (py/raise "KeyError" (py/py-repr nested-description)))))
                    ;; Python converts int to float on the way in
                    value (if (and (= "float" description) (= "int" (py/type-name value)))
                            (double value)
                            value)
                    answers (get possible-answer param)
                    typed (type-checker param value answers description expected nested)]
                (if-not (:valid typed)
                  (dissoc typed :variable?)
                  (let [special (when-not (:variable? typed)
                                  (cond
                                    (= "dict" expected) (dict-checker param value answers)
                                    (and (= "list" expected) (= "dict" nested))
                                    (list-dict-checker param value answers)
                                    (= "str" expected) (string-checker param value answers)
                                    (= "list" expected) (list-checker param value answers)))]
                    (cond
                      (and special (not (:valid special))) special
                      special nil
                      (py/py-in value answers) nil
                      :else
                      {:valid false
                       :error [(str "Invalid value for parameter " (py/py-repr param) ": " (py/py-repr value)
                                    ". Expected one of " (py/py-repr answers) ".")]
                       :error-type "value_error:others"}))))))
          model-params)
         (some (fn [[param answers]]
                 (when (and (not (contains? model-params param)) (not (py/py-in "" answers)))
                   {:valid false
                    :error [(str "Optional parameter " (py/py-repr param)
                                 " not provided and not marked as optional.")]
                    :error-type "simple_function_checker:missing_optional"}))
               possible-answer)
         {:valid true :error [] :error-type "simple_function_checker:unclear"})))))

(defn- find-description [functions function-name]
  (some #(when (= function-name (get % "name")) %) functions))

(defn- parallel-checker [functions model-output possible-answers]
  (if (not= (count model-output) (count possible-answers))
    {:valid false :error ["Wrong number of functions."]
     :error-type "parallel_function_checker_no_order:wrong_count"}
    (loop [answers (seq possible-answers)
           matched #{}]
      (if-not answers
        {:valid true :error []}
        (let [answer (first answers)
              function (find-description functions (key (first answer)))
              match (some (fn [index]
                            (when (and (not (matched index))
                                       (:valid (simple-function-checker
                                                function (nth model-output index) answer)))
                              index))
                          (range (count model-output)))]
          (if match
            (recur (next answers) (conj matched match))
            {:valid false
             :error [(str "Could not find a matching function among index "
                          (py/py-repr (vec (remove matched (range (count model-output)))))
                          " of model output for index "
                          (- (count possible-answers) (count answers))
                          " of possible answers.")]
             :error-type "parallel_function_checker_no_order:cannot_find_match"}))))))

(defn- multiple-checker [functions model-output possible-answers]
  (if (not= (count model-output) (count possible-answers))
    {:valid false :error ["Wrong number of functions."]
     :error-type "multiple_function_checker:wrong_count"}
    (let [answer (first possible-answers)]
      (simple-function-checker (find-description functions (key (first answer)))
                               (first model-output) answer))))

(defn ast-checker
  "Upstream `ast_checker` for the Python categories: `{:valid :error
   :error-type}`. `model-output` is the decoded calls, `[{tool-name {param
   value}}]` with tool names as the provider saw them (`tool-name`). An
   exception upstream would raise is returned as `:error-type
   \"exception:<PythonType>\"`; upstream's runner does not catch it, so such a
   task is a data fault there, not a verdict."
  [category functions model-output possible-answers]
  (try
    (cond
      (str/includes? category "parallel")
      (parallel-checker functions model-output possible-answers)

      (str/includes? category "multiple")
      (multiple-checker functions model-output possible-answers)

      (not= 1 (count model-output))
      {:valid false :error ["Wrong number of functions."]
       :error-type "simple_function_checker:wrong_count"}

      :else
      (simple-function-checker (first functions) (first model-output) (first possible-answers)))
    (catch clojure.lang.ExceptionInfo e
      (if (py/py-exception? e)
        {:valid false :error [(py/exception-message e)]
         :error-type (str "exception:" (py/exception-type e))}
        (throw e)))))

;; ---------------------------------------------------------------------------
;; From a response to a verdict (upstream `eval_runner.py`)

(defn function-calling-format?
  "`is_function_calling_format_output`: a list of single-entry dicts whose
   value is a dict. The empty list is in format."
  [decoded]
  (and (sequential? decoded)
       (every? (fn [item] (and (map? item) (= 1 (count item)) (map? (val (first item)))))
               decoded)))

(defn empty-output?
  "`is_empty_output`: nothing that counts as a function call."
  [decoded]
  (or (not (function-calling-format? decoded))
      (empty? decoded)
      (and (= 1 (count decoded)) (empty? (first decoded)))))

(defn grade
  "The verdict on one task: `{:valid :error :error-type}`. `calls` is what the
   candidate emitted in its first response, `[{tool-name arguments}]`, or nil
   when it answered in text."
  [{:keys [kind category functions ground-truth]} calls]
  (let [decoded (vec calls)]
    (case kind
      :irrelevance
      (if (empty-output? decoded)
        {:valid true :error []}
        {:valid false :error ["Valid syntax. Successfully decode AST when it should not."]
         :error-type "irrelevance_error:decoder_success"})

      :relevance
      (if (empty-output? decoded)
        {:valid false :error ["Invalid syntax. Failed to decode AST when it should have. "]
         :error-type "relevance_error:decoder_failed"}
        {:valid true :error []})

      :ast
      (if-not (function-calling-format? decoded)
        {:valid false :error ["Did not output in the specified format."]
         :error-type "ast_decoder:decoder_wrong_output_format"}
        (ast-checker category functions decoded ground-truth)))))

;; ---------------------------------------------------------------------------
;; Scores (upstream `eval_runner_helper.py`)

(defn summary
  "Accuracies from `results`, `{category {:correct n :total n}}`:

     :categories    accuracy per category
     :non-live-ast  UNWEIGHTED mean of simple_python, multiple, parallel and
                    parallel_multiple, present or not (upstream counts a
                    missing category as 0)
     :live-ast      mean of the four live AST categories WEIGHTED by their
                    task counts

   Upstream's leaderboard \"simple\" column is the mean of the Python, Java and
   JavaScript simple categories; only Python is transcribed, so `:non-live-ast`
   is comparable with upstream's \"Python simple\", multiple, parallel and
   parallel-multiple columns, not with its non-live overall."
  [results]
  (let [accuracy (fn [{:keys [correct total]}] (if (pos? (or total 0)) (/ (double correct) total) 0.0))
        of (fn [category] (get results category))
        live ["live_simple" "live_multiple" "live_parallel" "live_parallel_multiple"]
        live-total (reduce + (map #(:total (of %) 0) live))]
    {:categories (into (sorted-map) (map (fn [[c r]] [c (accuracy r)])) results)
     :non-live-ast (/ (reduce + (map #(accuracy (of %))
                                     ["simple_python" "multiple" "parallel" "parallel_multiple"]))
                      4.0)
     :live-ast (if (pos? live-total)
                 (/ (reduce + (map #(:correct (of %) 0) live)) (double live-total))
                 0.0)}))
