(ns dvergr.sandbox.ns.dev
  "SCI injectors — the agent dev kit: clojure.repl, clojure.repl.deps (gated
   add-libs via dvergr.sandbox.deps, inline-required), and hiccup HTML. Split out
   of dvergr.sandbox (Phase 4 decomposition)."
  (:require [sci.core :as sci]
            [hiccup.compiler :as hc]
            [hiccup.util :as hu]
            [hiccup2.core :as hiccup]))

;; ---------------------------------------------------------------------------
;; clojure.repl — discovery / introspection surface
;;
;; Standard Clojure functions exposed at their canonical names so the
;; agent's training data lines up:
;;   doc        — return docstring for a symbol or namespace
;;   find-doc   — search docstrings by string/regex
;;   apropos    — find symbols matching a pattern
;;   dir        — list public vars in a namespace
;;   dir-fn     — fn variant (dir is a macro in stdlib; we expose only the fn)
;;   pst        — print last exception's stack trace
;;   source-fn  — source code as a string (best-effort; nil when unknown)
;;
;; Everything operates on SCI's own namespace map, not the host JVM.
;; Stub variants return readable strings instead of side-effecting prints
;; so the agent's eval result captures the output.
;; ---------------------------------------------------------------------------

(defn- ns-or-throw [sym]
  (or (find-ns sym)
      (throw (ex-info (str "No namespace: " sym) {:type :no-namespace :sym sym}))))

(defn- safe-pattern [s]
  (cond
    (instance? java.util.regex.Pattern s) s
    (symbol? s) (re-pattern (java.util.regex.Pattern/quote (str s)))
    (string? s) (re-pattern (java.util.regex.Pattern/quote s))
    :else       (re-pattern (str s))))

(defn add-clojure-repl-ns!
  "Add clojure.repl namespace to SCI: discovery + introspection.

   Implements the safe, side-effect-free subset:
     doc, find-doc, apropos, dir-fn (and dir aliased to dir-fn),
     pst, root-cause, demunge, source-fn, stack-element-str.

   These operate against SCI's own namespace map. Agents can introspect
   what's been loaded in their sandbox without reaching into the host
   JVM. Functions that would normally print to *out* in stdlib here
   return a string instead, so the value is captured in the agent's
   tool result."
  [sci-ctx]
  (sci/add-namespace! sci-ctx 'clojure.repl
                      {'doc
                       (fn [sym]
                         (cond
                           (find-ns sym)
                           (or (some-> (find-ns sym) meta :doc str) "")

                           :else
                           (let [v (try (resolve sym) (catch Throwable _ nil))
                                 m (some-> v meta)]
                             (if m
                               (str (when (:macro m) "(macro) ")
                                    (:ns m) "/" (:name m)
                                    (when-let [a (:arglists m)] (str "\n  " (pr-str a)))
                                    (when-let [d (:doc m)] (str "\n\n  " d)))
                               (str "No doc found for " sym)))))

                       'find-doc
                       (fn [needle]
                         (let [re (safe-pattern needle)]
                           (vec
                            (for [ns* (all-ns)
                                  [_ v] (ns-publics ns*)
                                  :let  [m (meta v)
                                         text (str (:name m) " " (:doc m))]
                                  :when (re-find re text)]
                              (str (:ns m) "/" (:name m)
                                   (when-let [d (:doc m)] (str " — " d)))))))

                       'apropos
                       (fn [str-or-pattern]
                         (let [re (safe-pattern str-or-pattern)]
                           (vec
                            (sort
                             (for [ns* (all-ns)
                                   sym  (keys (ns-publics ns*))
                                   :when (re-find re (str sym))]
                               (symbol (str (ns-name ns*)) (str sym)))))))

                       'dir-fn
                       (fn [ns-sym]
                         (vec (sort (keys (ns-publics (ns-or-throw ns-sym))))))

                       'dir
                       (fn [ns-sym]
                         (vec (sort (keys (ns-publics (ns-or-throw ns-sym))))))

                       'source-fn
                       (fn [_sym]
       ;; SCI doesn't preserve source location for every var; return
       ;; nil rather than lying.
                         nil)

                       'root-cause
                       (fn [^Throwable t]
                         (loop [t t]
                           (if-let [c (.getCause t)] (recur c) t)))

                       'demunge
                       (fn [^String s]
                         (clojure.lang.Compiler/demunge s))

                       'stack-element-str
                       (fn [el]
                         (str el))

                       'pst
                       (fn pst
                         ([] "(pst without an exception arg — in SCI you should pass the exception explicitly: (pst e))")
                         ([^Throwable t]
                          (let [root (loop [^Throwable x t]
                                       (if-let [c (.getCause x)] (recur c) x))]
                            (with-out-str (.printStackTrace ^Throwable root)))))}))

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

