(ns dvergr.sandbox.ns.dev
  "SCI injectors — the agent dev kit: clojure.repl, clojure.repl.deps (gated
   add-libs via dvergr.sandbox.deps, inline-required), and hiccup HTML. Split out
   of dvergr.sandbox (Phase 4 decomposition)."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [hiccup.compiler :as hc]
            [hiccup.util :as hu]
            [hiccup2.core :as hiccup]))

;; ---------------------------------------------------------------------------
;; clojure.repl — discovery / introspection surface
;;
;; Standard Clojure functions exposed at their canonical names so the
;; agent's training data lines up:
;;   doc        — docstring + arglists for a var, or a listing for a namespace
;;   dir        — public vars of a namespace, with their signatures
;;   apropos    — find var names matching a pattern
;;   find-doc   — search names AND docstrings
;;   source     — explains that SCI retains no source text (source-fn → nil)
;;   pst        — format an exception using SCI's stacktrace
;;
;; Why this namespace has to be RIGHT rather than merely present: `doc` and
;; `dir` are what every Clojure programmer — and every Clojure-trained model —
;; reaches for by reflex on landing somewhere unfamiliar. A shim that exists but
;; misbehaves is worse than no shim at all, because the agent reads the failure
;; as evidence that the API it is EXPLORING is wrong rather than that its tool
;; is, and goes off guessing call shapes. (dvergr.sandbox's `sandbox/overview` +
;; `sandbox/doc` cover the same ground and have always worked — but nobody's
;; instinct spells them, so they can't be the only way in.)
;;
;; The one rule that keeps this honest: every lookup below reads the LIVE SCI
;; namespace map, `(:namespaces @(:env ctx))`, and nothing else. The previous
;; implementation called clojure.core's `find-ns` / `all-ns` / `ns-publics` /
;; `resolve`, which are the HOST JVM's. That was wrong on both sides of the
;; sandbox boundary at once: it could not see a single injected namespace
;; (`(dir 'kb)` → "No namespace: kb") while cheerfully enumerating the daemon's
;; own internals to the agent (`(apropos "secret")` →
;; `dvergr.substrate.config/secret-specs`, `(find-doc "telegram")` → the name
;; and docstring of the var holding the bot token).
;;
;; `doc`, `dir` and `source` are SCI MACROS (`^:sci/macro`, so SCI hands them
;; `&form`/`&env` first) because the canonical call is unquoted — `(doc
;; kb/attributes)`. The old fn version received the RESOLVED FN VALUE there and
;; died casting it to a Symbol. Each accepts the quoted form too; both spellings
;; are muscle memory and both must work.
;;
;; Return-value convention (the one the original docstring stated): stdlib
;; fns that PRINT return a formatted string here, so the value lands in the
;; agent's tool result. stdlib fns that return DATA still return data —
;; `apropos` and `dir-fn` hand back a vector of symbols, so `(count (apropos
;; …))` and `(filter …)` mean what they say.
;; ---------------------------------------------------------------------------

(defn- safe-pattern
  "Needles are matched case-INSENSITIVELY, and a plain string is quoted rather
   than compiled as a regex — an agent searching for \"page-add\" or \"(fn\"
   should get hits, not a PatternSyntaxException. An explicit Pattern is
   honoured verbatim, so the regex escape hatch survives."
  [s]
  (cond
    (instance? java.util.regex.Pattern s) s
    (or (symbol? s) (string? s)) (re-pattern (str "(?i)" (java.util.regex.Pattern/quote (str s))))
    :else (re-pattern (str "(?i)" (java.util.regex.Pattern/quote (str s))))))

;; --- reading the live SCI environment ---------------------------------------

(defn- sci-ns-map
  "The sandbox's namespace map, read fresh on every call so introspection sees
   namespaces injected — or `def`d by the agent — after this shim was installed."
  [sci-ctx]
  (:namespaces @(:env sci-ctx)))

(defn- sci-vars
  "The vars of one SCI namespace as {sym value}, or nil when there is no such
   namespace (nil and {} mean different things to the callers below).

   SCI keeps per-namespace bookkeeping under KEYWORD keys — `:aliases`, `:obj` —
   in the very same map as the vars, so every consumer must drop non-symbol keys
   before sorting or naming them, or it blows up with a Symbol/Keyword cast
   error. `dvergr.sandbox/ns-overview-data` learned this the same way."
  [sci-ctx ns-sym]
  (some->> (get (sci-ns-map sci-ctx) ns-sym)
           (into {} (filter (comp symbol? key)))))

(defn- resolve-ns
  "Resolve `sym` to a namespace name: itself when it names one, otherwise the
   target of a `require`d alias — `(dir fs)` after `(require '[babashka.fs :as
   fs])` is the same reflex as `(dir babashka.fs)`.

   Aliases are per-namespace in SCI and the agent's current eval namespace isn't
   knowable from here, so an alias resolves only when every namespace binding it
   agrees on one target. Ambiguity yields nil — a caller-visible miss with a
   list beats a silent guess."
  [sci-ctx sym]
  (let [nss (sci-ns-map sci-ctx)]
    (if (contains? nss sym)
      sym
      (let [targets (into #{} (keep (fn [[_ m]] (get (:aliases m) sym))) nss)]
        (when (= 1 (count targets)) (first targets))))))

(defn- all-sci-vars
  "Every [ns-sym var-sym value] in the sandbox — the search space for `apropos`
   and `find-doc`. It is the SCI environment in full, including the sandbox's
   own clojure.core: that is all sandbox surface, none of it host state."
  [sci-ctx]
  (for [[ns-sym _] (sci-ns-map sci-ctx)
        [sym v]    (sci-vars sci-ctx ns-sym)]
    [ns-sym sym v]))

(defn- no-ns-msg [sym]
  (str "No such namespace `" sym "` in this sandbox. Run `(sandbox/overview)` for "
       "the list, or `(clojure.repl/apropos \"" (name sym) "\")` to search by name."))

;; --- rendering --------------------------------------------------------------

(defn- signature
  "`(fn-name arg …)` per arity. Many sandbox namespaces are injected as bare fns
   via `sci/add-namespace!` and so carry no :arglists at all; say that outright
   instead of printing a name that looks like a zero-arg call."
  [sym m]
  (if-let [as (seq (:arglists m))]
    (str/join "  " (for [a as] (str "(" sym (when (seq a) (str " " (str/join " " a))) ")")))
    (str sym "  ; arity unknown")))

(defn- var-doc
  "Metadata rides on the VALUE in an injected namespace (a fn with meta) and on
   the var for anything `sci/copy-var`'d, and `meta` reads both — so one path
   covers the whole sandbox."
  [ns-sym sym v]
  (let [m (meta v)]
    (str ns-sym "/" sym "\n"
         (when (:macro m) "  (macro)\n")
         (if-let [as (seq (:arglists m))]
           (str "  " (pr-str (vec as)) "\n")
           "  ; arity unknown — injected as a bare fn\n")
         (if-let [d (:doc m)]
           (str "\n  " d)
           "  ; no docstring"))))

(defn- dir-str [sci-ctx ns-sym*]
  (if-let [ns-sym (resolve-ns sci-ctx ns-sym*)]
    (let [vars (sci-vars sci-ctx ns-sym)]
      (if (empty? vars)
        (str "`" ns-sym "` has no public vars.")
        (str "`" ns-sym "` — " (count vars) " public var" (when (not= 1 (count vars)) "s") "\n"
             (str/join "\n"
                       (for [[sym v] (sort-by key vars)
                             :let    [m (meta v)]]
                         (str "  " (signature sym m)
                              (when-let [d (:doc m)]
                                (str "\n      " (first (str/split-lines (str d)))))))))))
    (no-ns-msg ns-sym*)))

(defn- doc-str [sci-ctx sym]
  (cond
    (not (symbol? sym))
    (str "`doc` takes a symbol, got " (pr-str sym)
         ". Try `(clojure.repl/doc dvergr.room/kb-search)` or `(clojure.repl/doc dvergr.room)`.")

    (namespace sym)
    (if-let [ns-sym (resolve-ns sci-ctx (symbol (namespace sym)))]
      (let [vars (sci-vars sci-ctx ns-sym)
            nm   (symbol (name sym))]
        (if (contains? vars nm)
          (var-doc ns-sym nm (get vars nm))
          (str "No var `" nm "` in `" ns-sym "`. Its publics: "
               (str/join " " (sort (keys vars))))))
      (no-ns-msg (symbol (namespace sym))))

    ;; A bare symbol is either a namespace or an unqualified var name. Agents
    ;; type both, so try the namespace reading first and then hunt for the name
    ;; — an unambiguous hit is answered, an ambiguous one is listed to qualify.
    :else
    (if (resolve-ns sci-ctx sym)
      (dir-str sci-ctx sym)
      (let [hits (vec (for [[n s _] (all-sci-vars sci-ctx)
                            :when   (= s sym)]
                        (symbol (str n) (str s))))]
        (case (count hits)
          0 (str "No namespace or var named `" sym "` in this sandbox. "
                 "Run `(sandbox/overview)` for the list.")
          1 (doc-str sci-ctx (first hits))
          (str "`" sym "` is ambiguous — qualify it: " (str/join " " (sort hits))))))))

(defn- root-cause* [^Throwable t]
  (loop [^Throwable x t] (if-let [c (.getCause x)] (recur c) x)))

(defn- pst-str
  "Format `t` from SCI's OWN stacktrace — the frames of the code the agent
   wrote. `.printStackTrace` would dump the host interpreter's frames instead:
   dvergr package names and absolute source paths that mean nothing inside the
   sandbox and describe the daemon outside it. (It also wrote to System/err
   rather than *out*, so the old `with-out-str` wrapper returned \"\" to the
   agent every single time while the host trace went to the daemon's log.)

   Frames are only available when the exception PROPAGATED out of the
   interpreter — that is when SCI wraps it and attaches the callstack. An
   exception the agent catches itself arrives as the bare host exception with no
   callstack on it, so there is nothing to print; say that plainly rather than
   fall back to the host trace, which is the leak this shim exists to avoid."
  [^Throwable t]
  (let [root (root-cause* t)
        st   (try (some-> (sci/stacktrace t) sci/format-stacktrace)
                  (catch Throwable _ nil))]
    (str (.getSimpleName (class root)) ": " (.getMessage root)
         (when-let [d (ex-data root)] (str "\n  data: " (pr-str d)))
         (if (seq st)
           (str "\n" (str/join "\n" st))
           (str "\n  (no sandbox frames: SCI records them only for an exception that"
                " escapes your eval — re-run without the try/catch to see where it came from)")))))

;; --- installation -----------------------------------------------------------

(defn- sci-macro
  "Tag `f` as an SCI macro: SCI then passes `&form` and `&env` as its first two
   arguments, exactly as Clojure does."
  [f]
  (vary-meta f assoc :sci/macro true))

(defn- unquote-arg
  "`(doc kb/attributes)` hands the macro a bare symbol; `(doc 'kb/attributes)`
   hands it `(quote kb/attributes)`. Both spellings are reflex — accept either."
  [form]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(defn add-clojure-repl-ns!
  "Add clojure.repl to SCI: discovery + introspection over the SANDBOX.

   The side-effect-free subset, at the canonical names so the agent's training
   data lines up:

     (doc kb/attributes)   (doc 'kb/attributes)   (doc kb)     — var or namespace
     (dir kb)              (dir 'kb)              (dir-fn 'kb) — publics + signatures
     (apropos \"page\")                                          — vector of symbols
     (find-doc \"knowledge base\")                                — names AND docstrings
     (pst e)  (root-cause e)  (source-fn 'x)  (source x)  (demunge s)

   Every one of them reads THIS ctx's namespace map and nothing else — no host
   namespace, no host var, no host stack frame is reachable through here. See
   the block comment above for why that was worth doing properly.

   `doc`/`dir`/`source` are macros so the unquoted canonical form works; the
   quoted form works too. Misses return an actionable sentence naming
   `(sandbox/overview)`, never an empty string and never a raw throw."
  [sci-ctx]
  (sci/add-namespace!
   sci-ctx 'clojure.repl
   {;; The macros delegate to fns rather than expanding to a precomputed
    ;; string, so a `(doc …)` sitting inside an agent's helper fn reports the
    ;; sandbox as it is when CALLED, not as it was when the fn was defined.
    'doc      (sci-macro (fn [_&form _&env sym]
                           (list 'clojure.repl/doc-fn (list 'quote (unquote-arg sym)))))
    'doc-fn   (fn [sym] (doc-str sci-ctx sym))

    'dir      (sci-macro (fn [_&form _&env ns-sym]
                           (list 'clojure.repl/dir-str (list 'quote (unquote-arg ns-sym)))))
    'dir-str  (fn [ns-sym] (dir-str sci-ctx ns-sym))
    ;; stdlib's `dir-fn` returns the bare sorted symbols — keep that contract so
    ;; code that maps over it keeps working; `dir` is the human-readable one.
    'dir-fn   (fn [ns-sym]
                (if-let [ns* (resolve-ns sci-ctx ns-sym)]
                  (vec (sort (keys (sci-vars sci-ctx ns*))))
                  (throw (ex-info (no-ns-msg ns-sym) {:type :no-namespace :sym ns-sym}))))

    'apropos  (fn [str-or-pattern]
                (let [re (safe-pattern str-or-pattern)]
                  (vec (sort (for [[n s _] (all-sci-vars sci-ctx)
                                   :when   (re-find re (str s))]
                               (symbol (str n) (str s)))))))

    'find-doc (fn [needle]
                (let [re   (safe-pattern needle)
                      hits (sort (for [[n s v] (all-sci-vars sci-ctx)
                                       :let    [d (str (:doc (meta v)))]
                                       :when   (or (re-find re (str s)) (re-find re d))]
                                   (str n "/" s
                                        (when (seq d) (str "\n    " (first (str/split-lines d)))))))]
                  (if (seq hits)
                    (str/join "\n" hits)
                    (str "Nothing in this sandbox matches " (pr-str needle)
                         ". Run `(sandbox/overview)` for what's loaded."))))

    ;; SCI keeps no source text for a var, so `source-fn` returns nil (stdlib's
    ;; own contract for "unknown") rather than inventing something. `source` is
    ;; a macro only so the reflex spelling gets an explanation instead of an
    ;; "Unable to resolve symbol".
    'source-fn (fn [_sym] nil)
    'source    (sci-macro
                (fn [_&form _&env sym]
                  (str "SCI keeps no source text, so `source` can't show " (pr-str (unquote-arg sym))
                       ". `(clojure.repl/doc …)` gives its docstring and arglists; if YOU wrote"
                       " it, the file is in your workspace — `(slurp \"src/…\")`.")))

    'pst      (fn ([] "`pst` needs the exception: (pst e), e.g. (try … (catch Throwable e (pst e))).")
                ([t] (pst-str t)))
    'root-cause (fn [t] (root-cause* t))
    'demunge  (fn [^String s] (clojure.lang.Compiler/demunge s))
    'stack-element-str (fn [el] (str el))}))

;; ---------------------------------------------------------------------------
;; clojure.repl.deps — gated add-libs
;; ---------------------------------------------------------------------------

(defn add-clojure-repl-deps-ns!
  "Add clojure.repl.deps to SCI: gated add-libs.

   Agents call `(clojure.repl.deps/add-libs '{io.foo/bar {:mvn/version
   \"1.0\"}})`. The call is routed through `dvergr.sandbox.deps/add-libs!`
   which consults a policy fn (default: coord-pattern allowlist; off-list
   coords block until a human/manager calls
   `dvergr.sandbox.deps/approve!` or `deny!`).

   On approve, host classpath is updated AND every newly-loaded
   namespace is mirrored into this SCI ctx so the agent's subsequent
   `(require ...)` finds them.

   On deny, throws `ex-info` with :type :dvergr/dep-denied, :coord, :reason."
  [sci-ctx]
  (require 'dvergr.sandbox.deps)
  (let [add-libs!     @(ns-resolve 'dvergr.sandbox.deps 'add-libs!)
        sync-deps!    @(ns-resolve 'dvergr.sandbox.deps 'sync-deps!)
        make-load-fn* @(ns-resolve 'dvergr.sandbox.deps 'make-load-fn)]
    ;; Install :load-fn so SCI's `(require ...)` falls through to host
    ;; resolution + lazy namespace mirroring (gated by the denylist).
    ;; Without this, even after add-libs/sync-deps loads JARs onto the
    ;; host classpath, the agent's SCI-side `require` would still fail
    ;; — SCI has its own namespace map separate from the host.
    (sci/merge-opts sci-ctx {:load-fn (make-load-fn* sci-ctx)})
    (sci/add-namespace! sci-ctx 'clojure.repl.deps
                        {'add-libs  (fn [libs] (add-libs! sci-ctx libs))
                         'sync-deps (fn
                                      ([]     (sync-deps! sci-ctx))
                                      ([_kvs] (sync-deps! sci-ctx)))})))

;; ---------------------------------------------------------------------------
;; Hiccup HTML Generation
;; ---------------------------------------------------------------------------

(defn add-hiccup-ns!
  "Add hiccup2.core namespace to SCI context.

   Exposes hiccup as runtime functions so agents can generate HTML:
   - (require '[h]) or (h/html [:div ...])
   - (h/raw \"<b>bold</b>\")

   The html macro is wrapped as a function using hiccup.compiler/render-html
   so it works at runtime in SCI (macros need compile-time expansion).

   Example in agent code:
     (h/html [:div {:class \"card\"} [:h1 \"Hello\"]])
     ;; => \"<div class=\\\"card\\\"><h1>Hello</h1></div>\""
  [sci-ctx]
  (let [html-fn (fn [& body]
                  (hu/raw-string
                   (apply str (map hc/render-html body))))]
    (sci/add-namespace! sci-ctx 'hiccup2.core
                        {'html html-fn
                         'raw  hiccup/raw})
    (sci/add-namespace! sci-ctx 'h
                        {'html html-fn
                         'raw  hiccup/raw})))

;; ---------------------------------------------------------------------------
;; Context Manipulation
;; ---------------------------------------------------------------------------

