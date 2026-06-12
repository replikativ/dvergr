(ns dvergr.tools.code-analyzer
  "Simplified code analysis using rewrite-clj (syntax-level).
   
   Extracts metadata for indexing in datahike:
   - Namespace declarations
   - Function/var definitions (defn, def, defmethod, defmacro)
   - Argument lists
   - Docstrings
   - Function calls (for dependency tracking)
   
   Note: This is syntax-level only. It doesn't handle:
   - Macro expansion
   - Type inference
   - Dynamic calls
   
   For most agent use cases (finding callers, navigation, refactoring),
   syntax-level analysis is sufficient."
  (:require [rewrite-clj.zip :as z]
            [clojure.walk :as walk]))

(defn extract-ns-from-source
  "Extract namespace symbol from source code.
   Finds the first (ns ...) form."
  [source]
  (try
    (let [zloc (z/of-string source)]
      (loop [loc zloc]
        (cond
          (z/end? loc)
          nil

          (and (z/list? loc)
               (= "ns" (-> loc z/down z/sexpr str)))
          (try (-> loc z/down z/right z/sexpr)
               (catch Exception _ nil))

          :else
          (recur (z/next loc)))))
    (catch Exception _
      nil)))

(defn extract-calls-from-form
  "Extract function calls from a form.
   Returns set of qualified symbols (or unqualified if not resolvable)."
  [form]
  (let [calls (atom #{})]
    (walk/postwalk
     (fn [node]
       (when (and (list? node)
                  (symbol? (first node))
                  (not (contains? #{'fn 'fn* 'let 'if 'do 'quote} (first node))))
         (swap! calls conj (first node)))
       node)
     form)
    @calls))

(defn extract-def-metadata
  "Extract metadata from a def/defn/defmethod form at zipper location.
   
   Returns map with:
   - :type - :defn :def :defmethod :defmacro etc
   - :name - symbol name
   - :arglists - vector of arglists (for functions)
   - :doc - docstring
   - :line :column - location
   - :calls - set of symbols this def calls
   - :dispatch-val - for defmethod
   - :private? - if ^:private or (defn- ...)
   - :macro? - if defmacro"
  [zloc]
  (try
    (when (z/list? zloc)
      (let [form (z/sexpr zloc)
            def-type (first form)
            def-type-str (str def-type)]

        (when (re-matches #"def.*" def-type-str)
          (let [[row col] (try (z/position zloc)
                               (catch Exception _ [1 1]))

                ;; Extract based on def type
                result (case def-type-str
                         "defn"
                         (let [[_ name & rest] form
                               [doc-or-arglist & more] rest
                               doc (when (string? doc-or-arglist) doc-or-arglist)
                               arglists (if doc
                                          more
                                          (cons doc-or-arglist more))
                               ;; Filter out non-arglist metadata
                               arglists (filter vector? arglists)]
                           {:type :defn
                            :name name
                            :arglists (vec arglists)
                            :doc doc})

                         "defn-"
                         (let [[_ name & rest] form
                               [doc-or-arglist & more] rest
                               doc (when (string? doc-or-arglist) doc-or-arglist)
                               arglists (if doc more (cons doc-or-arglist more))
                               arglists (filter vector? arglists)]
                           {:type :defn
                            :name name
                            :arglists (vec arglists)
                            :doc doc
                            :private? true})

                         "def"
                         (let [[_ name & rest] form
                               doc (when (string? (first rest)) (first rest))]
                           {:type :def
                            :name name
                            :doc doc})

                         "defmacro"
                         (let [[_ name & rest] form
                               [doc-or-arglist & more] rest
                               doc (when (string? doc-or-arglist) doc-or-arglist)
                               arglists (if doc more (cons doc-or-arglist more))
                               arglists (filter vector? arglists)]
                           {:type :defmacro
                            :name name
                            :arglists (vec arglists)
                            :doc doc
                            :macro? true})

                         "defmethod"
                         (let [[_ multimethod-name dispatch-val & rest] form
                               [arglist & body] rest]
                           {:type :defmethod
                            :name multimethod-name
                            :arglists [arglist]
                            :dispatch-val dispatch-val})

                         "deftest"
                         (let [[_ name & _] form]
                           {:type :deftest
                            :name name})

                         nil)]

            (when result
              (assoc result
                     :line row
                     :column col
                     :calls (extract-calls-from-form form)))))))

    (catch Exception e
      nil)))

(defn extract-all-defs
  "Extract all def forms from source code.
   Returns vector of metadata maps."
  [source]
  (try
    (let [zloc (z/of-string source)]
      (loop [loc zloc
             results []]
        (if (z/end? loc)
          results
          (let [metadata (extract-def-metadata loc)
                results' (if metadata
                           (conj results metadata)
                           results)]
            (recur (z/next loc) results')))))
    (catch Exception e
      [])))

(defn analyze-file
  "Analyze a Clojure source file and extract indexable metadata.
   
   Arguments:
   - file-path: Path to file
   - source: Source code string
   
   Returns vector of maps suitable for datahike storage:
   {:ns :name :qualified-name :type :file :line :column
    :arglists :doc :calls :private? :macro? :dispatch-val}"
  [file-path source]
  (let [ns-sym (extract-ns-from-source source)
        defs (extract-all-defs source)]

    ;; Enrich each def with namespace and file info
    (mapv (fn [def-info]
            (let [name-sym (:name def-info)
                  qualified-name (when (and ns-sym name-sym)
                                   (symbol (str ns-sym) (str name-sym)))]
              (cond-> (assoc def-info
                             :ns ns-sym
                             :file file-path)

                qualified-name
                (assoc :qualified-name qualified-name)

                (:arglists def-info)
                (assoc :arglists (pr-str (:arglists def-info)))

                (:dispatch-val def-info)
                (assoc :dispatch-val (pr-str (:dispatch-val def-info)))

                (:calls def-info)
                (assoc :calls (vec (:calls def-info))))))
          defs)))

(comment
  ;; Test namespace extraction
  (def test-src "(ns sample.core)\n\n(defn greet [name]\n  (str \"Hello, \" name))")
  (extract-ns-from-source test-src)
  ;; => sample.core

  ;; Test def extraction
  (extract-all-defs test-src)
  ;; => [{:type :defn, :name greet, :arglists [[name]], 
  ;;      :line 3, :column 1, :calls #{str}}]

  ;; Test full analysis
  (analyze-file "test.clj" test-src)
  ;; => [{:ns sample.core, :name greet, :qualified-name sample.core/greet,
  ;;      :type :defn, :file "test.clj", :line 3, :column 1,
  ;;      :arglists "[[name]]", :calls [str]}]
  )
