(ns dvergr.sandbox.ns.malli
  "malli for agent code: the host's compiled `malli.core`, `malli.error`,
   `malli.provider`, `malli.util` and `malli.registry`, mirrored into SCI.

   Validation runs at compiled speed. What malli keeps in host globals is
   replaced so nothing leaks across sandboxes or forks:

   - `m/=>` is a sandbox macro. It registers the function schema in the
     CURRENT WORLD's state (`rtc/*execution-context*`), so a registration
     forks, merges and is discarded with the room, like the rest of the
     world. `m/function-schemas` reads the same registry.
   - The global mutators (`-register-function-schema!`, the deregisters and
     `malli.registry/set-default-registry!`) are not injected.

   Agents may also annotate with metadata, `(defn f {:malli/schema [...]} ...)`;
   `sandbox/doc` renders both forms."
  (:require [malli.core :as m]
            [malli.error]
            [malli.provider]
            [malli.registry]
            [malli.util]
            [org.replikativ.spindel.engine.core :as rtc]
            [sci.core :as sci]))

(def ^:private state-path [::fn-schemas])

(defn fn-schemas
  "`{ns-sym {name-sym {:form schema-form :ns :name}}}` registered with `m/=>`
   in the current world (empty without one)."
  []
  (or (when rtc/*execution-context* (rtc/get-state state-path)) {}))

(defn register!
  "Register `form` as the function schema of `ns-sym/name-sym` in the current
   world. The form is compiled eagerly, so an invalid schema fails at the
   `m/=>` call, not later."
  [ns-sym name-sym form]
  (m/function-schema form)
  (when-not rtc/*execution-context*
    (throw (ex-info "m/=> needs an execution context (the sandbox's world)" {:name name-sym})))
  (rtc/swap-state! state-path
                   (fn [s] (assoc-in (or s {}) [ns-sym name-sym]
                                     {:form form :ns ns-sym :name name-sym})))
  name-sym)

(defn- function-schemas
  "Sandbox `m/function-schemas`: malli's shape, `{ns {name {:schema :ns :name}}}`."
  ([] (function-schemas :clj))
  ([_key]
   (update-vals (fn-schemas)
                (fn [by-name]
                  (update-vals by-name
                               (fn [{:keys [form] :as e}]
                                 (-> e (dissoc :form) (assoc :schema (m/function-schema form)))))))))

(def ^:private arrow
  "`(m/=> sym schema)`: register `schema` for `sym` (resolved against the
   current namespace when unqualified)."
  (with-meta
    (fn [_form _env given-sym value]
      `(malli.core/-sandbox-register!
        ~(if (namespace given-sym)
           `'~(symbol (namespace given-sym))
           `(ns-name *ns*))
        '~(symbol (name given-sym))
        ~value))
    {:sci/macro true}))

(def ^:private withheld
  "Host-global mutators an agent must not reach."
  {'malli.core '#{-register-function-schema! -deregister-function-schemas!
                  -deregister-metadata-function-schemas!}
   'malli.registry '#{set-default-registry!}})

(defn- mirrored
  "SCI vars for the publics of host namespace `ns-sym`, keeping :doc and
   :arglists so `clojure.repl/doc` works on them. Host macros are skipped."
  [ns-sym]
  (let [sci-ns (sci/create-ns ns-sym nil)]
    (into {}
          (for [[sym v] (ns-publics ns-sym)
                :let [md (meta v)]
                :when (not (:macro md))
                :when (not (contains? (withheld ns-sym) sym))]
            [sym (sci/new-var sym @v (assoc (select-keys md [:doc :arglists]) :ns sci-ns))]))))

(defn add-malli-ns!
  "Inject malli into `sci-ctx` (see the ns doc)."
  [sci-ctx]
  (sci/add-namespace! sci-ctx 'malli.core
                      (assoc (mirrored 'malli.core)
                             '=> arrow
                             '-sandbox-register! register!
                             'function-schemas
                             (with-meta function-schemas
                               {:doc "Function schemas registered with m/=> in this sandbox's world: {ns {name {:schema :ns :name}}}."
                                :arglists '([] [key])})))
  (doseq [n '[malli.error malli.provider malli.util malli.registry]]
    (sci/add-namespace! sci-ctx n (mirrored n)))
  sci-ctx)
