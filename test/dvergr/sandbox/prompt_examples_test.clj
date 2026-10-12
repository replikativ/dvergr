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
   - each call to an injected fn matches the fn's malli schema when its
     arguments are literal, and its arity otherwise (`…` stands for the rest).

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

(defn- code-spans
  "The backticked spans of markdown-ish `text`, plus the bodies of its fenced
   code blocks."
  [text]
  (let [fenced (map second (re-seq #"(?s)```[a-z]*\n(.*?)```" text))
        prose  (str/replace text #"(?s)```[a-z]*\n.*?```" "")]
    (concat fenced (take-nth 2 (rest (str/split prose #"`" -1))))))

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

(defn- forms-in [text] (mapcat read-forms (code-spans text)))

(def ^:private placeholders
  "Names the text uses for the reader's own code (`my.ns`) or for elided parts."
  '#{my.ns my-test … ...})

(defn- placeholder? [x] (and (symbol? x) (contains? placeholders x)))

(def ^:private thread-first '#{-> some->})
(def ^:private thread-last '#{->> some->>})

(defn- calls
  "Every call in `form` (nested too) whose head is a qualified symbol, outside
   quoted data. A threaded step gets the threaded value as the symbol
   `threaded`, so it is checked with the arity it is called with."
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
                         (qualified-symbol? (first x)) (cons x))
              (coll? x) (mapcat walk x)))]
    (walk form)))

(defn- qualified-symbols [form]
  (->> (tree-seq (fn [x] (and (coll? x) (not (and (seq? x) (= 'quote (first x))))))
                 seq form)
       (filter qualified-symbol?)
       (remove #(= "clojure.core" (namespace %)))))

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
  (or (sci-var sci-ctx sym)
      (try (some? (sci/eval-string* sci-ctx (str "(resolve '" sym ")")))
           (catch Exception _ false))))

(defn- fn-meta [{:keys [value]}]
  (merge (meta value)
         (when (instance? clojure.lang.IDeref value)
           (try (meta @value) (catch Throwable _ nil)))))

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

(defn- schema-arity-ok? [schema n]
  (some (fn [input]
          (let [{:keys [min max]} (m/-regex-min-max (m/schema input) false)]
            (and (<= (or min 0) n) (or (nil? max) (<= n max)))))
        (input-schemas schema)))

(defn- call-problem
  "Why `call` (an injected fn called as shown) would not run, or nil."
  [sci-ctx registered call]
  (let [[head & args] call]
    (when-let [v (sci-var sci-ctx head)]
      (let [{:keys [arglists macro] :as md} (fn-meta v)
            schema (or (get-in registered [(:ns v) (symbol (name head)) :form])
                       (:malli/schema md))
            schema (when schema (m/schema schema))
            elided? (some placeholder? args)
            n (count args)]
        (cond
          (or macro elided?) nil
          (and schema (literal? args)
               (not-any? #(m/validate % (vec args)) (input-schemas schema)))
          (str (pr-str call) " does not match " (pr-str (m/form schema)))
          (and schema (not (schema-arity-ok? schema n)))
          (str (pr-str call) ": wrong arity for " (pr-str (m/form schema)))
          (and (not schema) (seq arglists) (not (arity-ok? arglists n)))
          (str (pr-str call) ": wrong arity for " (pr-str arglists)))))))

(defn- sandbox-ns? [sci-ctx nsn]
  (let [env @(:env sci-ctx)]
    (or (contains? (:namespaces env) (symbol nsn))
        (contains? (get-in env [:namespaces 'user :aliases]) (symbol nsn)))))

(defn- problems
  "Every problem with the forms of `text` in `sci-ctx`: requires that throw,
   names that do not resolve, calls that would not run. With `prose?` (a
   docstring, where parentheses also hold prose such as `(done/found)`), a
   name is only checked when its namespace is a sandbox one or dotted."
  [sci-ctx text & {:keys [prose?]}]
  (let [forms (forms-in text)
        registered (ns-malli/fn-schemas)
        root (stdlib-root)]
    (concat
     (eval-requires! sci-ctx forms)
     (for [sym (distinct (mapcat qualified-symbols forms))
           :let [nsn (namespace sym)]
           :when (not (placeholder? (symbol nsn)))
           :when (or (not prose?)
                     (and (not (str/includes? (name sym) "/"))
                          (or (str/includes? nsn ".") (sandbox-ns? sci-ctx nsn))))
           :when (if (stdlib-ns? nsn)
                   (and root (not (stdlib-defines? root nsn (name sym))))
                   (not (resolves? sci-ctx sym)))]
       (str sym " does not resolve"))
     (keep #(call-problem sci-ctx registered %) (distinct (mapcat calls forms))))))

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
          (is (empty? (problems sci-ctx (str "`" example "`"))) n)))
      (testing "every example in an injected fn's docstring"
        (doseq [[n d] (docstrings sci-ctx)]
          (is (empty? (problems sci-ctx (str "`" d "`") :prose? true)) n))))))

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
