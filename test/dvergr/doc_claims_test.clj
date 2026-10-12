(ns dvergr.doc-claims-test
  "The docs point at things that exist.

   Reads the repository's markdown (README, CLAUDE.md, CONTRIBUTING, doc/**,
   benchmarks/** outside the upstream resources, examples/**, notebooks/ and
   test/ READMEs) and the source trees, without loading any dvergr namespace,
   and checks three kinds of claim:

   - every relative markdown link resolves to a file or directory, and an
     `#anchor` on a markdown target names one of its headings (GitHub's
     slugs);
   - every backticked fully-qualified `ns/var` of dvergr or spindel names a
     var that the namespace's source defines (`def`, `defn`, `defmacro`,
     `defrecord`'s constructors, protocol methods, …), found on the classpath
     as source, so spindel's jar is read too; or, for a namespace of the
     agent's SCI sandbox (`dvergr.agent`, `dvergr.room`, …), a name the
     source that builds it binds;
   - every `doc/…md` path a source string, docstring or comment mentions
     exists (with its anchor, when it has one).

   Design history (doc/design/) is held to its links only. Intentional
   non-vars elsewhere go in `allowed-non-vars`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(def ^:private root (.getCanonicalFile (io/file ".")))

(defn- rel [^java.io.File f]
  (str (.relativize (.toPath root) (.toPath (.getCanonicalFile f)))))

(defn- files-under [dir pred]
  (let [d (io/file root dir)]
    (when (.exists d)
      (->> (file-seq d)
           (filter #(.isFile ^java.io.File %))
           (filter pred)
           (remove #(re-find #"(^|/)(target|node_modules|\.cpcache|\.git)/" (rel %)))))))

(defn- md? [^java.io.File f] (str/ends-with? (.getName f) ".md"))

(defn- doc-files
  "The markdown the lint reads."
  []
  (->> (concat (keep #(let [f (io/file root %)] (when (.exists f) f))
                     ["README.md" "CLAUDE.md" "CONTRIBUTING.md"
                      "notebooks/README.md" "test/README.md"])
               (files-under "doc" md?)
               (remove #(str/starts-with? (rel %) "benchmarks/resources/")
                       (files-under "benchmarks" md?))
               (files-under "examples" md?))
       (sort-by rel)))

(def ^:private source-dirs
  ["src" "src-clients" "benchmarks/src" "test" "benchmarks/test" "dev" "examples"])

(defn- source-files []
  (mapcat #(files-under % (fn [^java.io.File f] (re-find #"\.(clj|cljc|cljs|edn|py|sh)$" (.getName f))))
          source-dirs))

;; ---------------------------------------------------------------------------
;; Markdown
;; ---------------------------------------------------------------------------

(def ^:private fence #"(?ms)^\s*(```|~~~).*?^\s*\1[^\n]*$")

(defn- without-fences [text] (str/replace text fence ""))

(defn- without-code [text]
  (-> (without-fences text)
      (str/replace #"``[^`]*``" "")
      (str/replace #"`[^`\n]*`" "")))

(defn- heading-text
  "A heading's rendered text: links become their label, code and emphasis
   marks go."
  [s]
  (-> s
      (str/replace #"!?\[([^\]]*)\]\([^)]*\)" "$1")
      (str/replace #"<[^>]+>" "")
      str/trim))

(defn- slug
  "GitHub's anchor for a heading: lower case, punctuation other than `-` and
   `_` dropped, each space a `-`."
  [s]
  (-> (heading-text s)
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\p{M} _-]" "")
      (str/replace " " "-")))

(defn- anchors
  "Every anchor a markdown file defines: its headings' slugs (repeats get
   `-1`, `-2`, …) and explicit `id`/`name` attributes."
  [text]
  (let [body (without-fences text)
        heads (for [[_ h] (re-seq #"(?m)^#{1,6}\s+(.*?)\s*#*\s*$" body)] (slug h))
        numbered (loop [[h & more] heads seen {} acc []]
                   (if-not h
                     acc
                     (let [n (get seen h 0)]
                       (recur more (assoc seen h (inc n))
                              (conj acc (if (zero? n) h (str h "-" n)))))))]
    (into (set numbered)
          (map second (re-seq #"<a\s+(?:name|id)=\"([^\"]+)\"" body)))))

(def ^:private anchors-of
  (memoize (fn [path] (anchors (slurp (io/file root path))))))

(defn- links
  "The link targets of a markdown text, outside code."
  [text]
  (for [[_ target] (re-seq #"!?\[(?:[^\]\[]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)"
                           (without-code text))]
    target))

(defn- external? [target]
  (boolean (re-find #"^(?:[a-z][a-z0-9+.-]*:|//)" target)))

(defn- normalize [^String path]
  (str (.normalize (.toPath (io/file path)))))

(defn- check-target
  "nil when `target` (a path relative to the directory `dir`, maybe with an
   anchor) resolves, else why it does not."
  [dir target]
  (let [[path anchor] (str/split target #"#" 2)
        path (java.net.URLDecoder/decode ^String path "UTF-8")
        resolved (if (str/blank? path) nil (normalize (str dir "/" path)))
        file (when resolved (io/file root resolved))]
    (cond
      (and resolved (str/starts-with? resolved "..")) nil ; outside the repo (a sibling checkout)
      (and file (not (.exists ^java.io.File file))) (str "no such file " resolved)
      (and anchor (seq anchor) file (str/ends-with? resolved ".md")
           (not (contains? (anchors-of resolved) (str/lower-case anchor))))
      (str "no heading #" anchor " in " resolved))))

(defn- broken-links [^java.io.File f]
  (let [path (rel f)
        dir (or (.getParent (io/file path)) ".")
        text (slurp f)]
    (for [target (links text)
          :when (not (external? target))
          :let [why (if (str/starts-with? target "#")
                      (when-not (contains? (anchors text) (str/lower-case (subs target 1)))
                        (str "no heading " target))
                      (check-target dir target))]
          :when why]
      (str path ": " target " (" why ")"))))

;; ---------------------------------------------------------------------------
;; ns/var claims
;; ---------------------------------------------------------------------------

(def ^:private allowed-non-vars
  "Backticked qualified names the docs use on purpose without a var behind
   them."
  #{})

(def ^:private checked-ns?
  "The namespaces whose vars the docs are held to."
  #(or (str/starts-with? % "dvergr.") (str/starts-with? % "org.replikativ.spindel")))

(def ^:private qualified-symbol
  #"(?<![\w:.*+!?<>=/'-])((?:dvergr|org\.replikativ\.spindel)(?:\.[\w-]+)+)/([^\s`/()\[\]{},;\"'@^~#:][^\s`/()\[\]{},;\"'@^~]*)")

(defn- code-spans
  "The inline code and fenced Clojure of a markdown text."
  [text]
  (concat (for [[_ body] (re-seq #"(?ms)^\s*```(?:clojure|clj|edn)?\s*$(.*?)^\s*```" text)] body)
          (map second (re-seq #"`([^`\n]+)`" (without-fences text)))))

(defn- source-of
  "The source text of namespace `ns-name`, from the classpath, or nil."
  [ns-name]
  (let [base (-> ns-name (str/replace "-" "_") (str/replace "." "/"))]
    (some #(some-> (io/resource (str base %)) slurp) [".clj" ".cljc"])))

(defn- defined-names
  "The var names a namespace's source defines, read textually (the source is
   never loaded)."
  [src]
  (let [meta-skip "(?:\\^(?:\\{[^}]*\\}|[^\\s]+)\\s+)*"
        defs (for [[_ n] (re-seq (re-pattern (str "\\((?:[\\w.-]+/)?def[\\w-]*!?\\s+" meta-skip "([^\\s()\\[\\]{}]+)")) src)]
               n)
        records (for [[_ n] (re-seq #"\((?:defrecord|deftype)\s+([^\s()\[\]{}]+)" src)
                      x [(str "->" n) (str "map->" n)]]
                  x)
        protocol-methods (for [[_ body] (re-seq #"(?s)\(defprotocol\s+[^\s]+(.*?)(?=\n\(|\z)" src)
                               [_ m] (re-seq #"\(\s*([^\s()\[\]{}\"]+)\s+\[" body)]
                           m)
        ;; (declare a b) and the vars a ns-level `doseq`/macro makes are rare;
        ;; declare is the one that matters for forward references.
        declared (for [[_ names] (re-seq #"\(declare\s+([^)]*)\)" src)
                       n (str/split (str/trim names) #"\s+")]
                   n)]
    (set (concat defs records protocol-methods declared))))

(def ^:private names-of (memoize (fn [ns-name] (some-> (source-of ns-name) defined-names))))

(def ^:private sandbox-names
  "The namespaces the SCI sandbox gives an agent (`sci/add-namespace!` in
   src/), each with the quoted symbols of the source files that build it: a
   loose reading, but a name the docs show that no such file mentions is gone."
  (delay
    (apply merge-with into
           {}
           (for [f (files-under "src" #(re-find #"\.cljc?$" (.getName ^java.io.File %)))
                 :let [src (slurp f)
                       nss (map second (re-seq #"add-namespace!\s+[^\s']+\s+'([\w.-]+)" src))]
                 :when (seq nss)
                 :let [syms (set (map second (re-seq #"'([^\s()\[\]{}\"'`,;@^~#:][^\s()\[\]{}\"'`,;@^~]*)" src)))]
                 ns-name nss]
             {ns-name syms}))))

(def ^:private op-fn-names
  "The REPL's `dvergr.ops` fns, one per op of the spec: `:job/status` is
   `job-status` (`dvergr.mcp.repl/fn-name`)."
  (delay
    (set (for [[_ a b] (re-seq #"(?m)^\s+:([a-z][\w-]*)/([a-z][\w-]*)\s*$"
                               (slurp (io/file root "src/dvergr/ops.clj")))]
           (str a "-" b)))))

(defn- var-problem
  [ns-name var-name]
  (let [var-name (str/replace var-name #"[.,;:]+$" "")
        full (str ns-name "/" var-name)]
    (cond
      (contains? allowed-non-vars full) nil
      (not (checked-ns? ns-name)) nil
      :else
      (let [names (some-> (or (names-of ns-name) (get @sandbox-names ns-name))
                          (into (get @sandbox-names ns-name))
                          (into (when (= "dvergr.ops" ns-name) @op-fn-names)))]
        (cond
          (nil? names) (str "no namespace " ns-name)
          (= "*" var-name) nil          ; the namespace as a whole
          (not (contains? names var-name)) (str "no var " full))))))

(defn- unresolved-vars [^java.io.File f]
  (let [text (slurp f)]
    (distinct
     (for [span (code-spans text)
           [_ ns-name var-name] (re-seq qualified-symbol span)
           :let [why (var-problem ns-name var-name)]
           :when why]
       (str (rel f) ": `" ns-name "/" var-name "` (" why ")")))))

;; ---------------------------------------------------------------------------
;; doc paths in source
;; ---------------------------------------------------------------------------

(def ^:private workspace-doc-paths
  "doc/ paths of the agent's sandbox workspace (the dvergr-sandbox seed), not
   of this repository."
  #{"doc/INTAKES.md" "doc/EVIDENCE.md"})

(def ^:private doc-path
  #"(?<![\w./-])((?:benchmarks/)?doc/[\w./-]*?\.md)(#[\w-]+)?")

(defn- missing-doc-paths [^java.io.File f]
  (let [text (slurp f)]
    (distinct
     (for [[_ path anchor] (re-seq doc-path text)
           :when (not (contains? workspace-doc-paths path))
           :let [why (check-target "." (str path anchor))]
           :when why]
       (str (rel f) ": " path anchor " (" why ")")))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest slugs-follow-github
  (is (= "whats-new-in-v2" (slug "What's new in `v2`?")))
  (is (= "tau2-bench-banking_knowledge-bm25-retrieval"
         (slug "tau2-bench (banking_knowledge, `bm25` retrieval)")))
  (is (= "a--b" (slug "a & b")))
  (is (= #{"x" "x-1"} (anchors "# x\n\n```\n# not a heading\n```\n## x\n"))))

(deftest the-lint-reads-what-it-should
  (testing "it finds the docs and the source"
    (is (some #(= "doc/README.md" (rel %)) (doc-files)))
    (is (not-any? #(str/starts-with? (rel %) "benchmarks/resources/") (doc-files)))
    (is (seq (source-files))))
  (testing "it reads a var out of source without loading it"
    (is (contains? (names-of "dvergr.doc-claims-test") "slug"))
    (is (nil? (var-problem "dvergr.doc-claims-test" "slug")))
    (is (some? (var-problem "dvergr.doc-claims-test" "no-such-var"))))
  (testing "a sandbox namespace and the REPL's op fns count as defined"
    (is (nil? (var-problem "dvergr.agent" "hire!")))
    (is (some? (var-problem "dvergr.agent" "no-such-fn")))
    (is (nil? (var-problem "dvergr.ops" "job-status"))))
  (testing "links outside code are read, and a missing file or heading is caught"
    (is (= ["b.md" "c.md#x"] (links "[a](b.md) `[no](d.md)` ![i](c.md#x)\n```\n[no](e.md)\n```\n")))
    (is (nil? (check-target "doc" "runs.md")))
    (is (some? (check-target "doc" "no-such-page.md")))
    (is (some? (check-target "doc" "runs.md#no-such-heading"))))
  (testing "there is something to check"
    (is (< 200 (count (mapcat (comp links slurp) (doc-files)))))
    (is (< 100 (count (for [f (doc-files) span (code-spans (slurp f))
                            m (re-seq qualified-symbol span)] m))))))

(deftest relative-links-resolve
  (let [broken (mapcat broken-links (doc-files))]
    (is (empty? broken) (str "Broken links:\n" (str/join "\n" broken)))))

(deftest qualified-names-in-docs-exist
  ;; doc/design/ is history: it names what was planned, built or not.
  (let [missing (mapcat unresolved-vars (remove #(str/starts-with? (rel %) "doc/design/") (doc-files)))]
    (is (empty? missing) (str "Names the docs show that the code does not define:\n"
                              (str/join "\n" missing)))))

(deftest doc-paths-in-source-exist
  (let [missing (mapcat missing-doc-paths (source-files))]
    (is (empty? missing) (str "doc/ paths the source mentions that do not exist:\n"
                              (str/join "\n" missing)))))
