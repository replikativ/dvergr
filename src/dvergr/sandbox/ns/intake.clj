(ns dvergr.sandbox.ns.intake
  "Native intake mounts for the SCI sandbox.

   Almost all intakes now live as agent-modifiable SOURCE in the sandbox stdlib (cloned from ../dvergr-sandbox)
   stdlib (`resources/sandbox stdlib (cloned from ../dvergr-sandbox)/intake/*.clj`), seeded into every room repo
   and loaded via the workspace `:load-fn` — agents `(require '[intake.hn])` and
   read/copy/extend the source. They compose over the sandbox capability
   primitives (`http`/`json`/`url`/`base64`/`xml`/`html`, see dvergr.sandbox.ns.codec).

   Only sources that genuinely can't be interpreted stay native and are mounted
   here — currently just `intake.mail` (briefkasten + javax.mail are too heavy)."
  (:require [dvergr.effects :as effects]
            [dvergr.substrate.load :as load]
            [sci.core :as sci]))

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
    (load/require! 'dvergr.intake.mail)
    (let [mail-ns (find-ns 'dvergr.intake.mail)]
      (some-> (resolve-mail-bindings mail-ns)
              ;; host-side, not mounted: whether a read must open the store
              (assoc 'open? (some-> (ns-resolve mail-ns 'account-open?) deref))))
    (catch Throwable _
      nil)))

(defn- account-of
  "The `:account` keyword argument in `args`, or the default account."
  [args]
  (or (second (drop-while #(not= :account %) args)) :datahike-contact))

(defn gate-mail-bindings
  "`bindings` for the sandbox, through the boundary `effects`
   (`dvergr.effects`): `sync!`, which pulls an IMAP account into the local
   store, is `:mail/sync`; a read of an account whose store is not open yet
   opens it, a `:mail/open` (a write); reads of an open store are not effects.
   `open?` (host-side, not mounted) answers whether an account is open."
  [{open? 'open? :as bindings} effects]
  (let [opening (fn [read]
                  (fn [& args]
                    (let [account (account-of args)]
                      (if (and open? (open? account))
                        (apply read args)
                        (effects/perform! effects {:effect :mail/open :resource {:account (name account)}}
                                          #(apply read args))))))]
    (-> (dissoc bindings 'open?)
        (update 'inbox opening)
        (update 'search opening)
        (update 'read opening)
        (update 'sync!
                (fn [sync!]
                  (fn [& {:keys [account folders] :as opts}]
                    (effects/perform! effects {:effect :mail/sync
                                               :resource {:account (name (or account :datahike-contact))
                                                          :folders (vec (or folders ["INBOX"]))}}
                                      #(apply sync! (mapcat identity opts)))))))))

(defn add-intake-namespaces!
  "Mount the few NATIVE-only intake namespaces. Everything else is sandbox
   source. `effects` is the sandbox's boundary."
  [sci-ctx & [effects]]
  ;; intake.mail — OPTIONAL: its clojure-mail/postal/briefkasten deps live in the
  ;; :cli/:tui/:dev aliases, not core. Mounted only when present.
  (when-let [bindings (load-mail-bindings)]
    (sci/add-namespace! sci-ctx 'intake.mail (gate-mail-bindings bindings effects)))
  sci-ctx)
