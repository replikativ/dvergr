(ns dvergr.sandbox.ns.intake
  "Native intake mounts for the SCI sandbox.

   Almost all intakes now live as agent-modifiable SOURCE in the sandbox stdlib (cloned from ../dvergr-sandbox)
   stdlib (`resources/sandbox stdlib (cloned from ../dvergr-sandbox)/intake/*.clj`), seeded into every room repo
   and loaded via the workspace `:load-fn` — agents `(require '[intake.hn])` and
   read/copy/extend the source. They compose over the sandbox capability
   primitives (`http`/`json`/`url`/`base64`/`xml`/`html`, see dvergr.sandbox.ns.codec).

   Only sources that genuinely can't be interpreted stay native and are mounted
   here — currently just `intake.mail` (briefkasten + javax.mail are too heavy)."
  (:require [sci.core :as sci]))

(def ^:private native-mail-vars
  {'inbox  'list-inbox
   'search 'search-mail
   'read   'read-message
   'sync!  'sync-inbox!})

(defn- resolve-mail-bindings [mail-ns]
  ;; Resolve against one Namespace snapshot. In a cold/concurrent process an
  ;; optional `require` can fail in one sandbox setup while another setup sees
  ;; the library as loaded; a second symbol-based ns-resolve then throws when
  ;; the partially loaded namespace has already disappeared. Resolve all Vars
  ;; first, then snapshot their callable roots for SCI.
  (when mail-ns
    (let [bindings (into {}
                         (map (fn [[sandbox-name host-name]]
                                [sandbox-name (ns-resolve mail-ns host-name)]))
                         native-mail-vars)]
      (when (every? var? (vals bindings))
        (update-vals bindings deref)))))

(defn- load-mail-bindings []
  (try
    (require 'dvergr.intake.mail)
    (resolve-mail-bindings (find-ns 'dvergr.intake.mail))
    (catch Throwable _
      nil)))

(defn add-intake-namespaces!
  "Mount the few NATIVE-only intake namespaces. Everything else is sandbox source."
  [sci-ctx]
  ;; intake.mail — OPTIONAL: its clojure-mail/postal/briefkasten deps live in the
  ;; :cli/:tui/:dev aliases, not core. Mounted only when present.
  (when-let [bindings (load-mail-bindings)]
    (sci/add-namespace! sci-ctx 'intake.mail bindings))
  sci-ctx)
