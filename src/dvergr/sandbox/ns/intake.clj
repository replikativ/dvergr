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

(defn add-intake-namespaces!
  "Mount the few NATIVE-only intake namespaces. Everything else is sandbox source."
  [sci-ctx]
  ;; intake.mail — OPTIONAL: its clojure-mail/postal/briefkasten deps live in the
  ;; :cli/:tui/:dev aliases, not core. Mounted only when present.
  (when (try (require 'dvergr.intake.mail) true (catch Throwable _ false))
    (sci/add-namespace! sci-ctx 'intake.mail
                        {'inbox  @(ns-resolve 'dvergr.intake.mail 'list-inbox)
                         'search @(ns-resolve 'dvergr.intake.mail 'search-mail)
                         'read   @(ns-resolve 'dvergr.intake.mail 'read-message)
                         'sync!  @(ns-resolve 'dvergr.intake.mail 'sync-inbox!)}))
  sci-ctx)
