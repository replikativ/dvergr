(ns dvergr.test-registries
  "Undo what a test did to the process-wide channel and tool registries, and
   nothing else.

   Tools register when their namespace loads (the schedule and mail tools load
   when a daemon starts), so a test may be the first to load one. Restoring a
   snapshot of the whole registry would drop those tools for the rest of the
   run, and a later test would find them missing depending on test order
   (#278). So a test's own changes are undone one by one:

   - the channels it connected are disconnected, which unregisters their
     tools from both registries, and the channels it disconnected come back;
   - the tool and MCP entries it added are removed, and those that existed
     before and that it replaced or removed get their values back.

   An entry added while a namespace was being loaded (`*file*` names a source
   file) is the namespace's registration, not the test's, and stays."
  (:require [dvergr.channels.core :as ch]
            [dvergr.mcp.server :as mcp]
            [dvergr.tools :as tools]))

(defn- loading?
  "True while a namespace is being loaded on this thread."
  []
  (not (contains? #{"NO_SOURCE_PATH" "NO_SOURCE_FILE"} *file*)))

(defn- watch-additions!
  "Record in `added` the names that `names-of` finds in a new value of `a` but
   not in the old one, unless a namespace is loading."
  [a k names-of added]
  (add-watch a k (fn [_ _ old new]
                   (when-not (loading?)
                     (swap! added into (remove (set (names-of old))) (names-of new))))))

(defn snapshot
  "The registries as they are now. Until `restore!`, it records the tool and
   MCP entries added outside a namespace load."
  []
  (let [k (keyword (str *ns*) (str "snapshot-" (random-uuid)))
        added (atom #{})]
    (watch-additions! tools/registry k keys added)
    (watch-additions! mcp/tool-handlers k keys added)
    (watch-additions! mcp/tool-definitions k #(map :name %) added)
    {:watch k
     :added added
     :channels @ch/channels
     :tools @tools/registry
     :mcp-tools @mcp/tool-definitions
     :mcp-handlers @mcp/tool-handlers}))

(defn- restore-changed
  "`now` with every entry of `before` that differs from it put back."
  [now before]
  (reduce-kv (fn [m k v] (if (= v (get m k ::absent)) m (assoc m k v)))
             now before))

(defn restore!
  "Undo the changes made to the registries since `snap` was taken."
  [snap]
  (doseq [a [tools/registry mcp/tool-handlers mcp/tool-definitions]]
    (remove-watch a (:watch snap)))
  (let [before-channels (:channels snap)]
    (doseq [id (keys @ch/channels)
            :when (not= (get @ch/channels id) (get before-channels id))]
      (try (ch/disconnect! id) (catch Throwable _ nil)))
    (swap! ch/channels restore-changed before-channels))
  (let [before-tools (:tools snap)
        before-handlers (:mcp-handlers snap)
        before-defs (into {} (map (juxt :name identity)) (:mcp-tools snap))
        added @(:added snap)]
    (swap! tools/registry
           #(restore-changed (apply dissoc % (remove (set (keys before-tools)) added))
                             before-tools))
    (swap! mcp/tool-handlers
           #(restore-changed (apply dissoc % (remove (set (keys before-handlers)) added))
                             before-handlers))
    (swap! mcp/tool-definitions
           (fn [defs]
             (let [defs (remove #(and (contains? added (:name %))
                                      (not (contains? before-defs (:name %))))
                                defs)
                   now (into {} (map (juxt :name identity)) defs)
                   changed (remove #(= (val %) (get now (key %))) before-defs)
                   changed-names (into #{} (map key) changed)]
               (into (vec (remove #(contains? changed-names (:name %)) defs))
                     (map val) changed)))))
  ;; the MCP tools derived from the registry follow it as it is now
  (mcp/sync-registry-tools!)
  nil)

(defn fixture
  "A clojure.test fixture: `restore!` after each test."
  [f]
  (let [snap (snapshot)]
    (try (f) (finally (restore! snap)))))
