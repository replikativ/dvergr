(ns dvergr.sandbox.prompt-examples-test
  "Every call the agent-facing text shows exists, and takes what it is given.

   Agents copy the examples in the sandbox system prompt
   (`sandbox/sandbox-prompt-pointer`), the skill index, the `(sandbox/overview)`
   guide, the docstrings of the injected fns and the skill files. An example
   that names a missing fn, or passes a string where the fn takes a map, costs
   every candidate a failed eval (and, since the prompt is in each LLM Run's
   inputs id, fixing it later re-runs certified cells). This test reads every
   Clojure form in that text, in the full sandbox an agent turn gets
   (`turn/new-working-ctx`), and checks:

   - each `require` it shows loads (placeholder namespaces such as `my.ns`
     aside);
   - each qualified symbol resolves, through the aliases those requires make;
   - each call to an injected fn matches the fn's malli schema (its literal
     arguments at their positions) or its arity (`…` stands for the rest);
   - each unqualified call names something the example defines, binds or
     refers;
   - each Clojure block, and each backticked call, reads.

   The guide's `dvergr.agent` and `spindel.work` examples also run in a room.

   Intake and mail namespaces are sandbox stdlib SOURCE (../dvergr-sandbox,
   or `DVERGR_SANDBOX_REPO`); names in them are checked against that checkout
   when there is one. `dvergr.scheduler.core-test` additionally runs the
   prompt's scheduler calls against a room."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.ns.data :as data-ns]
            [dvergr.orchestration.skills :as skills]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.malli :as ns-malli]
            [dvergr.test-support :as support]
            [malli.core :as m]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]
            [sci.core :as sci]))

;; ---------------------------------------------------------------------------
;; Reading forms out of prose
;; ---------------------------------------------------------------------------

(def ^:private fence #"(?s)```([a-z]*)\n(.*?)```")

(defn- code-spans
  "The code of markdown-ish `text`: the bodies of its fenced blocks and its
   backticked spans, as {:code s :block? bool :clojure? bool}."
  [text]
  (let [fenced (for [[_ lang body] (re-seq fence text)]
                 {:code body :block? true :clojure? (= "clojure" lang)})
        prose  (str/replace text fence "")]
    (concat fenced
            (for [span (take-nth 2 (rest (str/split prose #"`" -1)))]
              {:code span}))))

(defn- read-all
  "Every form in `s`, read in order, or ::unreadable."
  [s]
  (try (binding [*read-eval* false]
         (with-open [r (java.io.PushbackReader. (java.io.StringReader. s))]
           (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} r))))))
       (catch Exception _ ::unreadable)))

(defn- read-forms
  "Every list form that reads from `s`, tried at each `(`; prose between forms
   is skipped."
  [s]
  (loop [i 0 acc []]
    (let [j (str/index-of s "(" i)]
      (if-not j
        acc
        (let [rdr (java.io.PushbackReader. (java.io.StringReader. (subs s j)))
              [form used] (try (binding [*read-eval* false]
                                 (let [f (read rdr)
                                       ;; chars consumed: what is left unread
                                       left (count (slurp rdr))]
                                   [f (- (count s) j left)]))
                               (catch Exception _ [nil 1]))]
          (recur (+ j (max 1 used)) (cond-> acc (seq? form) (conj form))))))))

(defn- forms-in [text] (mapcat (comp read-forms :code) (code-spans text)))

(defn- unreadable
  "The examples of `text` that do not read: a Clojure block, or a backticked
   span that starts as a call."
  [text]
  (for [{:keys [code block? clojure?]} (code-spans text)
        :when (or clojure? (and (not block?) (str/starts-with? (str/trim code) "(")))
        :when (= ::unreadable (read-all code))]
    (str "unreadable example: " (pr-str code))))

(def ^:private placeholders
  "Names the text uses for the reader's own code (`my.ns`) or for elided parts."
  '#{my.ns my-test … ...})

(defn- placeholder? [x] (and (symbol? x) (contains? placeholders x)))

(def ^:private thread-first '#{-> some->})
(def ^:private thread-last '#{->> some->>})

(defn- calls
  "Every call in `form` (nested too) outside quoted data. A threaded step gets
   the threaded value as the symbol `threaded`, so it is checked with the
   arity it is called with."
  [form]
  (letfn [(step [op x]
            (let [x (if (seq? x) x (list x))]
              (if (thread-first op)
                (list* (first x) 'threaded (rest x))
                (concat x ['threaded]))))
          (walk [x]
            (cond
              (and (seq? x) (= 'quote (first x))) nil
              (and (seq? x) (or (thread-first (first x)) (thread-last (first x))))
              (let [[op init & steps] x]
                (concat (walk init) (mapcat #(walk (step op %)) steps)))
              (seq? x) (cond->> (mapcat walk x)
                         (symbol? (first x)) (cons x))
              (coll? x) (mapcat walk x)))]
    (walk form)))

(defn- qualified-symbols [form]
  (->> (tree-seq (fn [x] (and (coll? x) (not (and (seq? x) (= 'quote (first x))))))
                 seq form)
       (filter qualified-symbol?)
       (remove #(= "clojure.core" (namespace %)))))

(def ^:private binders
  '#{let let* loop loop* binding when-let if-let when-some if-some for doseq dotimes
     with-open fn fn* defn defn- defmacro letfn})

(defn- bound-symbols
  "The symbols `forms` define or bind (def names, params, let bindings):
   calls to these are the text's own code."
  [forms]
  (set (for [x (mapcat #(tree-seq coll? seq %) forms)
             :when (and (seq? x) (symbol? (first x)))
             :let [[op a b] x]
             s (cond
                 (#{'def 'defn 'defn- 'defmacro} op)
                 (cons a (filter symbol? (tree-seq coll? seq (filter vector? (take 4 (rest x))))))
                 (binders op)
                 (filter symbol? (tree-seq coll? seq (filter vector? [a b])))
                 :else nil)
             :when (symbol? s)]
         s)))

(defn- requires [forms]
  (->> forms (mapcat #(tree-seq coll? seq %))
       (filter #(and (seq? %) (= 'require (first %))))))

;; ---------------------------------------------------------------------------
;; The sandbox an agent turn gets
;; ---------------------------------------------------------------------------

(defn- with-agent-sandbox [f]
  (let [ec (ctx/create-execution-context)]
    (try
      (let [cctx (turn/new-working-ctx {:execution-ctx ec :title "prompt-examples"
                                        :durable? false})]
        (binding [rtc/*execution-context* ec]
          (f (chat-context/sci-context-in cctx ec))))
      (finally (ctx/stop-context! ec)))))

(def ^:private stdlib-prefixes ["dvergr.intake." "dvergr.mail."])

(defn- stdlib-ns? [ns-name]
  (some #(str/starts-with? (str ns-name) %) stdlib-prefixes))

(defn- stdlib-root []
  (let [env (System/getenv "DVERGR_SANDBOX_REPO")
        root (io/file (if (and env (.isDirectory (io/file env))) env "../dvergr-sandbox"))]
    (when (.exists (io/file root "dvergr" "intake")) root)))

(defn- stdlib-defines?
  "Does the stdlib checkout `root` define public `sym` in `ns-name`?"
  [root ns-name sym]
  (let [f (io/file root (str (-> (str ns-name) (str/replace "-" "_") (str/replace "." "/")) ".clj"))]
    (and (.exists f)
         (boolean (re-find (re-pattern (str "\\((?:defn|def|defmacro) \\Q" sym "\\E[\\s\\n]"))
                           (slurp f))))))

(defn- lib-of [spec]
  (let [spec (if (and (seq? spec) (= 'quote (first spec))) (second spec) spec)]
    (if (vector? spec) (first spec) spec)))

(defn- required-libs [forms]
  (set (mapcat #(map lib-of (rest %)) (requires forms))))

(defn- eval-requires!
  "Evaluate the `require`s in `forms` in `sci-ctx`; the problems, as strings."
  [sci-ctx forms]
  (keep (fn [form]
          (let [libs (map lib-of (rest form))]
            (when-not (some #(or (placeholder? %) (stdlib-ns? %)) libs)
              (try (sci/eval-string* sci-ctx (pr-str form)) nil
                   (catch Exception e (str (pr-str form) " throws: " (ex-message e)))))))
        (requires forms)))

(defn- sci-var
  "The injected value `sym` names in `sci-ctx` (through `user`'s aliases), or
   nil when it names nothing."
  [sci-ctx sym]
  (let [env @(:env sci-ctx)
        alias-ns (get-in env [:namespaces 'user :aliases (symbol (namespace sym))])
        ns-sym (or alias-ns (symbol (namespace sym)))]
    (when-let [nsmap (get-in env [:namespaces ns-sym])]
      (when (contains? nsmap (symbol (name sym)))
        {:ns ns-sym :value (get nsmap (symbol (name sym)))}))))

(defn- resolves? [sci-ctx sym]
  (or (and (qualified-symbol? sym) (sci-var sci-ctx sym))
      (try (some? (sci/eval-string* sci-ctx (str "(resolve '" sym ")")))
           (catch Exception _ false))))

(def ^:private special-forms
  '#{def if do let* fn* loop* letfn* recur quote var throw try catch finally new set!
     case* . monitor-enter monitor-exit})

(defn- interop? [sym]
  (let [n (name sym)]
    (or (str/starts-with? n ".") (str/ends-with? n "."))))

(defn- fn-meta [{:keys [value]}]
  (merge (meta value)
         (when (instance? clojure.lang.IDeref value)
           (try (meta @value) (catch Throwable _ nil)))))

(defn- host-arity-ok?
  "Does host fn `f` (no arglists, no schema) take `n` arguments, by its
   compiled `invoke` methods? nil when that cannot be told."
  [f n]
  (let [f (if (instance? clojure.lang.IDeref f) (try @f (catch Throwable _ nil)) f)]
    (when (instance? clojure.lang.AFunction f)
      (let [fixed (->> (.getDeclaredMethods (class f))
                       (filter #(= "invoke" (.getName ^java.lang.reflect.Method %)))
                       (map #(count (.getParameterTypes ^java.lang.reflect.Method %)))
                       set)
            rest-min (when (instance? clojure.lang.RestFn f)
                       (.getRequiredArity ^clojure.lang.RestFn f))]
        (when (or (seq fixed) rest-min)
          (or (contains? fixed n) (and rest-min (>= n rest-min))))))))

(defn- literal? [x]
  (not-any? #(and (symbol? %) (not= 'quote %))
            (tree-seq coll? seq x)))

(defn- arity-ok? [arglists n]
  (some (fn [al]
          (let [[fixed rest-args] (split-with #(not= '& %) al)]
            (if (seq rest-args) (>= n (count fixed)) (= n (count fixed)))))
        arglists))

(defn- input-schemas [schema]
  (let [s (m/form schema)]
    (if (= :function (first s))
      (map second (rest s))
      [(second s)])))

(def ^:private regex-ops #{:? :* :+ :alt :cat :catn :altn :repeat})

(defn- input-ok?
  "Can arguments `args` (literal ones as given, the rest unknown) satisfy the
   input schema `input`? Literal arguments are checked against the schema at
   their position when the schema is a plain `[:cat …]`; otherwise only the
   arity is, unless every argument is literal."
  [input args]
  (let [form (m/form input)
        n (count args)
        {:keys [min max]} (m/-regex-min-max (m/schema input) false)
        arity? (and (<= (or min 0) n) (or (nil? max) (<= n max)))]
    (cond
      (not arity?) false
      (literal? args) (m/validate input (vec args))
      (and (vector? form) (= :cat (first form))
           (not-any? #(and (vector? %) (regex-ops (first %))) (rest form)))
      (every? (fn [[s a]] (or (not (literal? a)) (m/validate s a)))
              (map vector (rest form) args))
      :else true)))

(defn- call-problem
  "Why `call` (an injected fn called as shown) would not run, or nil."
  [sci-ctx registered call]
  (let [[head & args] call]
    (when-let [v (and (qualified-symbol? head) (sci-var sci-ctx head))]
      (let [{:keys [arglists macro] :as md} (fn-meta v)
            schema (or (get-in registered [(:ns v) (symbol (name head)) :form])
                       (:malli/schema md))
            schema (when schema (m/schema schema))
            n (count args)]
        (cond
          (or macro (some placeholder? args)) nil
          schema
          (when-not (some #(input-ok? % args) (input-schemas schema))
            (str (pr-str call) " does not match " (pr-str (m/form schema))))
          (seq arglists)
          (when-not (arity-ok? arglists n)
            (str (pr-str call) ": wrong arity for " (pr-str arglists)))
          :else
          (when (false? (host-arity-ok? (:value v) n))
            (str (pr-str call) ": wrong arity")))))))

(defn- sandbox-ns? [sci-ctx nsn]
  (let [env @(:env sci-ctx)]
    (or (contains? (:namespaces env) (symbol nsn))
        (contains? (get-in env [:namespaces 'user :aliases]) (symbol nsn)))))

(defn- problems
  "Every problem with the forms of `text` in `sci-ctx`: examples that do not
   read, requires that throw, names that do not resolve, calls that would not
   run. With `prose?` (a docstring, where parentheses also hold prose such as
   `(done/found)`), only qualified names whose namespace is a sandbox one or
   dotted are checked. The requires a text makes are undone afterwards, so
   one text's aliases never make another's examples pass."
  [sci-ctx text & {:keys [prose?]}]
  (let [user (get-in @(:env sci-ctx) [:namespaces 'user])]
    (try
      (let [forms (forms-in text)
            registered (ns-malli/fn-schemas)
            root (stdlib-root)
            bound (bound-symbols forms)
            required (required-libs forms)]
        (doall
         (concat
          (when-not prose? (unreadable text))
          (eval-requires! sci-ctx forms)
          (for [sym (distinct (mapcat qualified-symbols forms))
                :let [nsn (namespace sym)]
                :when (not (placeholder? (symbol nsn)))
                :when (or (not prose?)
                          (and (not (str/includes? (name sym) "/"))
                               (or (str/includes? nsn ".") (sandbox-ns? sci-ctx nsn))))
                :let [why (if (stdlib-ns? nsn)
                            (cond
                              (not (contains? required (symbol nsn)))
                              (str sym ": its namespace is not required in the example")
                              (and root (not (stdlib-defines? root nsn (name sym))))
                              (str sym " does not resolve"))
                            (when-not (resolves? sci-ctx sym)
                              (str sym " does not resolve")))]
                :when why]
            why)
          (when-not prose?
            (for [head (distinct (keep #(let [h (first %)] (when (simple-symbol? h) h))
                                       (mapcat calls forms)))
                  :when (not (or (placeholder? head) (bound head) (special-forms head)
                                 (interop? head) (str/starts-with? (name head) "%")))
                  :when (not (resolves? sci-ctx head))]
              (str head " does not resolve")))
          (keep #(call-problem sci-ctx registered %) (distinct (mapcat calls forms))))))
      (finally
        (swap! (:env sci-ctx) assoc-in [:namespaces 'user] user)))))

;; ---------------------------------------------------------------------------
;; The texts
;; ---------------------------------------------------------------------------

(defn- skill-index-text []
  (skills/skill-prompt-section [{:name "x" :description "y"}]))

(def ^:private agent-facing-namespaces
  '#{dvergr.room dvergr.agent dvergr.agents dvergr.actors dvergr.skills dvergr.tasks
     dvergr.scheduler dvergr.codec dvergr.mail git env llm sandbox spindel.work
     doc vision dvergr.shell babashka.http-client})

(defn- docstrings
  "The docstrings of the fns injected into the agent-facing namespaces, as
   [\"ns/sym\" doc]."
  [sci-ctx]
  (for [[ns-sym vars] (:namespaces @(:env sci-ctx))
        :when (contains? agent-facing-namespaces ns-sym)
        [sym v] vars
        :when (symbol? sym)
        :let [d (:doc (fn-meta {:value v}))]
        :when d]
    [(str ns-sym "/" sym) d]))

(deftest the-sandbox-prompt-shows-calls-that-run
  (with-agent-sandbox
    (fn [sci-ctx]
      (testing "the system-prompt pointer"
        (let [forms (forms-in sandbox/sandbox-prompt-pointer)]
          (is (< 20 (count forms)) "the prompt's forms are read")
          (is (empty? (problems sci-ctx sandbox/sandbox-prompt-pointer)))))
      (testing "the skill index appended to the system prompt"
        (is (empty? (problems sci-ctx (skill-index-text))))))))

(deftest the-sandbox-guide-shows-calls-that-run
  (with-agent-sandbox
    (fn [sci-ctx]
      (testing "every `(sandbox/overview)` example"
        (doseq [[n [_ example]] @#'sandbox/ns-guide]
          ;; the guide's examples are bare code, not backticked prose
          (is (seq (forms-in (str "`" example "`"))) (str n ": its example reads"))
          (is (empty? (problems sci-ctx (str "`" example "`") :prose? false)) n)))
      (testing "every example in an injected fn's docstring"
        (doseq [[n d] (docstrings sci-ctx)]
          (is (empty? (problems sci-ctx (str "`" d "`") :prose? true)) n))))))

(deftest the-guides-run-examples-run-in-a-room
  (testing "the `dvergr.agent` race and the `spindel.work` example evaluate as shown"
    (let [room (discourse/make-room {:id :prompt-examples-guide :store (memory/make)})
          sci-ctx (sandbox/fork-for-session (:ctx room))]
      (try
        (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) nil)
        (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room)
                                        {:room-id (:id room)
                                         :room-incarnation (:incarnation room)})
        (binding [rtc/*execution-context* (:ctx room)]
          (let [run (fn [n]
                      (let [example (second (get @#'sandbox/ns-guide n))]
                        ;; from the code on: the agent example opens with prose
                        (sandbox/eval-code sci-ctx (subs example (str/index-of example "(require"))
                                           :timeout-ms 30000)))]
            (is (= :fast (:value (run "dvergr.agent"))) "the first Run's reply wins")
            (let [r (run "spindel.work")]
              (is (:success r) (pr-str (:error r)))
              (is (:terminal? (:value r))))))
        (finally (ctx/stop-context! (:ctx room)))))))

(defn- skill-files []
  (->> (file-seq (io/file "resources/skills"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (sort-by str)))

(deftest the-skills-name-what-exists
  (when-not (stdlib-root)
    (support/skip! "the-skills-name-what-exists: no ../dvergr-sandbox checkout; intake names unchecked"))
  (doseq [f (skill-files)]
    ;; each skill reads in a fresh sandbox: its requires make its aliases
    (with-agent-sandbox
      (fn [sci-ctx]
        (is (empty? (problems sci-ctx (slurp f))) (str f))))))
