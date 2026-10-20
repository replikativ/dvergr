(ns dvergr.mcp.host-file-reads-test
  "Inactive trusted host reads through ACTUAL MCP dispatch; no daemon, provider,
   transport listener, Simmis provisioning, or new credential capability."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dvergr.mcp.json-rpc :as rpc]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.surface :as surface]
            [dvergr.ops :as ops]
            [dvergr.room.registry :as registry]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.targeted-files-test :as fixture]
            [dvergr.substrate.geschichte :as workspace]
            [geschichte.repo :as repo]
            [jsonista.core :as json]
            [muschel.fs :as fs]
            [muschel.fs.geschichte :as mounted]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(def ^:private versions ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"])
(def ^:private alice {:principal/id "party/explicit-alice" :principal/kind :human
                      :principal/scopes #{:dvergr/read-only}
                      :principal/tools #{"room_files" "room_read"}})

(defn- session [version principal]
  (let [context (server/session-context (fn [_]) (surface/selection {:profile :admin}) principal)]
    (when-not (= "2026-07-28" version)
      (rpc/handle-message context {:jsonrpc "2.0" :id 1 :method "initialize"
                                   :params {:protocolVersion version :capabilities {} :clientInfo {:name "host-read-test" :version "1"}}})
      (rpc/handle-message context {:jsonrpc "2.0" :method "notifications/initialized"}))
    (fn [method params]
      ;; Actual serialization preserves namespaced metadata keys and UUID text.
      (let [params (cond-> params
                     (= "2026-07-28" version)
                     (assoc :_meta {:io.modelcontextprotocol/protocolVersion version
                                    :io.modelcontextprotocol/clientInfo {:name "host-read-test" :version "1"}
                                    :io.modelcontextprotocol/clientCapabilities {}}))
            result (rpc/handle-message context {:jsonrpc "2.0" :id 2 :method method :params params})]
        (json/read-value
         (json/write-value-as-string result
                                     (json/object-mapper {:encode-key-fn #(if (keyword? %) (subs (str %) 1) (str %))}))
         json/keyword-keys-object-mapper)))))

(defn- tool [send name args] (send "tools/call" {:name name :arguments args}))
(defn- data [response]
  (is (nil? (:error response)) (pr-str response))
  (is (not (get-in response [:result :isError])) (pr-str response))
  (json/read-value (get-in response [:result :content 0 :text]) json/keyword-keys-object-mapper))

(defn- with-host [owned-first? f]
  (@#'fixture/with-repositories
   (if owned-first? ["room-repo-owned" "room-repo-other"] ["room-repo-other" "room-repo-owned"])
   (fn [room repositories]
     (registry/register! room)
     (let [owned (:conn (get repositories "room-repo-owned"))
           other (:conn (get repositories "room-repo-other"))
           stamp (str (random-uuid)) calls (atom []) allowed? (atom true)
           resolver (fn [principal request]
                      (is (= (:principal/id alice) (:principal/id principal))
                          "Explicit transport principal, not ambient ops identity")
                      (swap! calls conj request)
                      {:db @owned :target-stamp stamp})
           host {:execution-ctx (:ctx room)
                 :config {:auth {:authorize (fn [principal _]
                                              {:allow? (and @allowed? (= (:principal/id alice) (:principal/id principal)))})}
                          :room-files {:resolve-read resolver}}}]
       (with-redefs [server/current-daemon (constantly host)]
         (f {:room room :host host :owned owned :other other :stamp stamp :calls calls :allowed? allowed?}))))))

(deftest tools-and-both-resource-paths-share-one-owned-snapshot-in-every-protocol
  (doseq [owned-first? [true false]]
    (with-host
      owned-first?
      (fn [{:keys [room owned other stamp calls]}]
        (repo/write! owned "docs/a.md" (.getBytes "Owned document" "UTF-8"))
        (let [selected (workspace/selected-system-id) before @other handle (name (:id room))]
          (doseq [version versions]
            (let [send (session version alice)
                  read-result (data (tool send "room_read" {:room handle :path "identity.txt"}))]
              (is (= "room-repo-owned" (:text read-result)))
              (is (= stamp (:target-stamp read-result)))
              (is (= stamp (:target-stamp (data (tool send "room_files" {:room handle})))))
              (let [resource (send "resources/read" {:uri (str "room://" handle "/files/identity.txt")})
                    directory (send "resources/read" {:uri (str "room://" handle "/files/docs")})
                    derived (send "resources/read" {:uri (str "room://" handle "/files")})]
                (is (= "room-repo-owned" (get-in resource [:result :contents 0 :text])))
                (is (= stamp (get-in resource [:result :contents 0 :_meta :dvergr/target-stamp])))
                (is (= ["a.md"] (mapv :name (:entries (json/read-value (get-in directory [:result :contents 0 :text])
                                                                       json/keyword-keys-object-mapper)))))
                (is (= stamp (get-in directory [:result :contents 0 :_meta :dvergr/target-stamp])))
                (is (= stamp (:target-stamp (json/read-value (get-in derived [:result :contents 0 :text])
                                                             json/keyword-keys-object-mapper)))))
              (is (= "room-repo-owned" (:text (data (tool send "room_read"
                                                          {:room handle :path "identity.txt" :expected-target-stamp stamp})))))))
          (is (= (* 6 (count versions)) (count @calls)) "ONE resolver call per tool/resource, directory fallback included")
          (is (= #{:list :read :resource} (set (map :operation @calls))))
          (is (every? #(= handle (:room %)) @calls))
          (is (= selected (workspace/selected-system-id)))
          (is (= before @other)))))))

(deftest directory-fallback-and-file-bodies-use-the-returned-db-not-a-later-target
  (with-host
    true
    (fn [{:keys [room host owned stamp]}]
      (repo/write! owned "docs/pinned.md" (.getBytes "Pinned" "UTF-8"))
      (let [calls (atom 0) before @owned
            resolver (fn [principal request]
                       (is (= alice principal) "Resource ignores poisoned ambient ops principal")
                       (swap! calls inc)
                       (repo/write! owned "docs/later.md" (.getBytes "Later" "UTF-8"))
                       {:db before :target-stamp stamp})
            host (assoc-in host [:config :room-files :resolve-read] resolver)]
        (with-redefs [server/current-daemon (constantly host)]
          (binding [ops/*principal* {:principal/id "not-the-transport-principal"}]
            (let [send (session "2025-11-25" alice)
                  reply (send "resources/read" {:uri (str "room://" (name (:id room)) "/files/docs")})]
              (is (= ["pinned.md"] (mapv :name (:entries (json/read-value (get-in reply [:result :contents 0 :text])
                                                                          json/keyword-keys-object-mapper)))))
              (is (= 1 @calls)))))))))

(deftest configured-errors-and-invalid-bindings-never-leak-or-selected-fall-back
  (with-host
    true
    (fn [{:keys [room host owned stamp calls]}]
      (let [handle (name (:id room))
            bad-configs [nil {:resolve-read nil} {:resolve-read :not-a-function}
                         {:resolve-read (fn [& _] nil)}
                         {:resolve-read (fn [& _] {:db @owned :target-stamp ""})}
                         {:resolve-read (fn [& _] {:db @owned :target-stamp stamp :conn owned})}
                         {:resolve-read (fn [& _] (throw (ex-info "SECRET-HOST-DESCRIPTOR" {:secret "SECRET-EX-DATA"})))}]]
        (doseq [config bad-configs]
          (with-redefs [server/current-daemon (constantly (assoc-in host [:config :room-files] config))]
            (let [send (session "2025-11-25" alice)]
              (doseq [reply [(tool send "room_read" {:room handle :path "identity.txt"})
                             (send "resources/read" {:uri (str "room://" handle "/files/identity.txt")})]]
                (is (or (:error reply) (get-in reply [:result :isError])))
                (is (not (str/includes? (pr-str reply) "SECRET")))
                (is (not (str/includes? (pr-str reply) "room-repo-other")))))))
        (is (empty? @calls))))))

(deftest stamps-and-current-permission-refuse-before-body-read-without-new-ceilings
  (with-host
    false
    (fn [{:keys [room host owned calls allowed?]}]
      (let [handle (name (:id room)) send (session "2025-11-25" alice)
            read-only (session "2025-11-25" (assoc alice :principal/tools #{"room_read"}))]
        (with-redefs [files/read-file-in (fn [& _] (throw (AssertionError. "Stale target reached body projection")))]
          (is (get-in (tool send "room_read" {:room handle :path "identity.txt" :expected-target-stamp "old-target"}) [:result :isError])))
        (is (= 1 (count @calls)))
        (is (:error (tool read-only "room_files" {:room handle})))
        (repo/write! owned "docs/x.txt" (.getBytes "Owned directory" "UTF-8"))
        (is (nil? (:error (read-only "resources/read" {:uri (str "room://" handle "/files/docs")})))
            "Directory fallback is authorized as room_read without room_files capability")
        (reset! allowed? false)
        (let [n (count @calls)]
          (is (get-in (tool send "room_read" {:room handle :path "identity.txt"}) [:result :isError]))
          (is (:error (send "resources/read" {:uri (str "room://" handle "/files/identity.txt")})))
          (is (= n (count @calls))))
        (with-redefs [simple/on-drain-thread? (constantly true)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (ops/room-file-read host alice :read {:room handle :path "identity.txt"}))))))))

(deftest unconfigured-host-retains-selected-mountfs-and-refuses-unsupported-stamps
  (with-host
    true
    (fn [{:keys [room host owned other]}]
      (let [handle (name (:id room))
            legacy (update host :config dissoc :room-files)
            filesystem (workspace/filesystem (workspace/current-workspace))]
        (fs/mkdir filesystem "/mounted")
        (mounted/mount-repository! filesystem "/mounted" {:conn owned} {:allow-nested? true})
        (with-redefs [server/current-daemon (constantly legacy)
                      workspace/filesystem (constantly filesystem)]
          (let [send (session "2025-11-25" alice)]
            (is (= "room-repo-other" (:text (data (tool send "room_read" {:room handle :path "identity.txt"})))))
            (is (= ["identity.txt"] (mapv :name (:entries (data (tool send "room_files" {:room handle :path "/mounted"}))))))
            (is (get-in (tool send "room_read" {:room handle :path "identity.txt" :expected-target-stamp "not-supported"}) [:result :isError]))))))))
