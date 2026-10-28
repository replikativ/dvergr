(ns dvergr.sandbox.extensions
  "Sandbox extensions: namespaces an AgentDef asks for by name.

   An AgentDef's `:sandbox` is a set of keywords. Each names an extension in
   this process-local registry: host code that installs namespaces into the
   agent's SCI interpreter. The AgentDef stays portable data (a name, never
   code), and since the sandbox is part of the candidate's action space, two
   AgentDefs that differ only in it are two candidates on the same
   environment (doc/benchmarks.md).

   An extension is `{:version pos-int :basis data :install (fn [sci-ctx
   execution-ctx])}`, or `:install` a qualified symbol resolved when first
   installed. `:basis` is what a score obtained with the extension depends on
   (e.g. a library's version); runs record `refs`. `install` runs on every
   rebind of a working context, so it must be idempotent. An optional
   `:release` (fn [sci-ctx]) frees what an interpreter's installation holds
   (background work) when the interpreter is released.

   Built in: `:foerster/inference` (probabilistic inference,
   `dvergr.sandbox.ns.data/install-foerster!`)."
  (:require [dvergr.agent.roster :as roster]))

(defn- foerster-version []
  (or (some-> (Thread/currentThread) .getContextClassLoader
              (.getResource "META-INF/maven/org.replikativ/foerster/pom.properties")
              slurp
              (->> (re-find #"(?m)^version=(.+)$"))
              second)
      :unreleased))

(defonce ^:private registry
  (atom {:foerster/inference {:version 1
                              :basis {:foerster (delay (foerster-version))}
                              :install 'dvergr.sandbox.ns.data/install-foerster!
                              :release 'dvergr.sandbox.ns.data/release-foerster!}}))

(defn register!
  "Register extension `id` (a qualified keyword). Replaces an existing one."
  [id {:keys [version basis install] :as extension}]
  (when-not (qualified-keyword? id)
    (throw (ex-info "A sandbox extension id is a qualified keyword" {:type ::invalid-id :id id})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info "A sandbox extension :version is a positive integer" {:type ::invalid-version :id id})))
  (doseq [[k f] [[:install install] [:release (:release extension)]]
          :when (or (= :install k) (some? f))]
    (when-not (or (fn? f) (qualified-symbol? f))
      (throw (ex-info (str "A sandbox extension " k " is a function or a qualified symbol")
                      {:type ::invalid-install :id id}))))
  (when-not (or (nil? basis) (and (map? basis) (roster/data-value? basis)))
    (throw (ex-info "A sandbox extension :basis is a map of portable data" {:type ::invalid-basis :id id})))
  (swap! registry assoc id extension)
  id)

(defn- lookup [id]
  (or (get @registry id)
      (throw (ex-info (str "Unknown sandbox extension " id)
                      {:type ::unknown-extension :id id :known (set (keys @registry))}))))

(defn- realized-basis [basis]
  (into {} (map (fn [[k v]] [k (if (delay? v) @v v)])) basis))

(defn checked-ids
  "`ids` as a set of registered extension ids; throws for anything else, so
   an agent naming an unknown extension fails where it is configured."
  [ids]
  (when-not (and (coll? ids) (not (map? ids)) (every? keyword? ids))
    (throw (ex-info "A sandbox is a collection of extension keywords"
                    {:type ::invalid-sandbox :sandbox ids})))
  (run! lookup ids)
  (set ids))

(defn refs
  "[{:extension/id :extension/version :extension/basis} …] of `ids`, sorted;
   throws for an unknown one."
  [ids]
  (vec (for [id (sort ids)
             :let [{:keys [version basis]} (lookup id)]]
         {:extension/id id :extension/version version :extension/basis (realized-basis basis)})))

(defn install!
  "Install the extensions `ids` into `sci-ctx` for `execution-ctx`; throws
   for an unknown one before installing any."
  [sci-ctx execution-ctx ids]
  (let [extensions (mapv (fn [id] [id (lookup id)]) (sort ids))]
    (doseq [[_ {:keys [install]}] extensions]
      ((if (symbol? install) (requiring-resolve install) install) sci-ctx execution-ctx))
    sci-ctx))

(defn snapshot
  "Capture the exact installation and release functions for an acquisition.
   Resolve before allocating a new interpreter. Later registry replacement must
   not choose a different destructor for resources already acquired. HOST only."
  [ids]
  (let [resolve-fn (fn [f] (if (symbol? f) @(requiring-resolve f) f))]
    (into (sorted-map)
          (map (fn [id]
                 (let [extension (lookup id)]
                   [id (-> extension
                           (update :install resolve-fn)
                           (update :release resolve-fn))])))
          ids)))

(defn install-snapshot!
  "Install a captured acquisition plan in its owning interpreter."
  [sci-ctx execution-ctx plan]
  (doseq [[_ {:keys [install]}] (sort-by key plan)]
    (install sci-ctx execution-ctx))
  sci-ctx)

(defn release!
  "Release what the extensions `ids` hold for `sci-ctx` (their `:release`);
   unknown ids have no acquired resources. Callback failures propagate so the
   caller can retain ownership; returning is the callback's cleanup ACK."
  [sci-ctx ids]
  (doseq [id (sort ids)
          :let [release (:release (get @registry id))]
          :when (and sci-ctx release)]
    ((if (symbol? release) (requiring-resolve release) release) sci-ctx)))
