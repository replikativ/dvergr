(ns dvergr.mcp.host-catalog-reads-test
  "Actual MCP dispatch with isolated native stores, no inference or host wiring."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.catalog :as catalog]
            [dvergr.catalog.immutable-test :as source]
            [dvergr.catalog.room :as room]
            [dvergr.mcp.host-file-reads-test :as host]
            [dvergr.mcp.server :as server]
            [dvergr.ops :as ops]
            [dvergr.rooms.files :as files]
            [dvergr.substrate.geschichte :as workspace]
            [geschichte.query :as query]
            [jsonista.core :as json]))

(def ^:private versions ["2024-11-05" "2025-03-26" "2025-06-18" "2025-11-25" "2026-07-28"])
(def ^:private principal
  {:principal/id "party/explicit-alice" :principal/kind :human
   :principal/scopes #{:dvergr/read-only} :principal/tools #{"catalog_list"}})

(defn- tool [send name args] (send "tools/call" {:name name :arguments args}))
(defn- data [response]
  (is (nil? (:error response)) (pr-str response))
  (is (not (get-in response [:result :isError])) (pr-str response))
  (json/read-value (get-in response [:result :content 0 :text]) json/keyword-keys-object-mapper))

(deftest catalog-projects-owned-source-not-selected-source
  (doseq [owned-first? [true false]]
    (@#'host/with-host
     owned-first?
     (fn [{:keys [host owned other stamp calls]}]
       (source/seed! owned "insight" source/valid-source)
       (source/seed! other "wrong" (assoc source/valid-source "workflow.edn" "{:title \"Other\" :task \"Wrong\"}"))
       (let [selection (workspace/selected-system-id)
             snapshot (fn [] (let [db @other]
                               {:commit (d/commit-id db) :config (:config db)
                                :worktree (query/worktree db)}))
             other-before (snapshot)
             original (str (random-uuid))
             expected (str (:id (room/bundle "insight" source/valid-source)))]
         (with-redefs [room/list-bundles (fn [& _] (throw (AssertionError. "Selected listing bypass")))
                       room/read-bundle (fn [& _] (throw (AssertionError. "Selected source bypass")))
                       room/run-checker (fn [& _] (throw (AssertionError. "Checker executed")))
                       room/calibrate (fn [& _] (throw (AssertionError. "Calibration executed")))
                       room/tier (constantly :ad-hoc)
                       files/write-to! (fn [& _] (throw (AssertionError. "Native writer executed")))]
           (doseq [version versions]
             (let [send (@#'host/session version principal)
                   rows (data (tool send "catalog_list" {:room original}))
                   authored (filter :target-stamp rows)]
               (is (= 1 (count authored)))
               (is (= expected (:bundle (first authored))))
               (is (= (str original "/insight") (:id (first authored))))
               (is (= stamp (:target-stamp (first authored))))
               (is (= "Owned" (:title (first authored))))
               (is (or (:error (tool send "room_read" {:room original :path "identity.txt"}))
                       (get-in (tool send "room_read" {:room original :path "identity.txt"}) [:result :isError])))
               (is (or (:error (tool send "catalog_start" {:workflow (str original "/insight")}))
                       (get-in (tool send "catalog_start" {:workflow (str original "/insight")}) [:result :isError])))))
           (is (= (count versions) (count @calls)))
           (is (every? #(= {:operation :list :room original :path "/workflows"} %) @calls))
           (is (= selection (workspace/selected-system-id)))
           (is (= other-before (snapshot)))))))))

(deftest one-pinned-db-covers-discovery-definition-and-fixtures
  (@#'host/with-host
   true
   (fn [{:keys [host owned stamp]}]
     (source/seed! owned "insight" source/valid-source)
     (let [db @owned calls (atom 0) original "cold-opaque-display-handle"
           resolver (fn [actual request]
                      (is (= principal actual) "Explicit transport principal")
                      (is (= {:operation :list :room original :path "/workflows"} request))
                      (swap! calls inc)
                      (source/seed! owned "insight" (assoc source/valid-source "fixtures/input.md" "Later"))
                      (source/seed! owned "later" source/valid-source)
                      {:db db :target-stamp stamp})]
       (with-redefs [server/current-daemon (constantly (assoc-in host [:config :room-files :resolve-read] resolver))
                     room/tier (constantly :ad-hoc)]
         (binding [ops/*principal* {:principal/id "poisoned-ambient"}]
           (let [send (@#'host/session "2026-07-28" principal)
                 authored (filter :target-stamp (data (tool send "catalog_list" {:room original})))]
             (is (= [(str original "/insight")] (mapv :id authored)))
             (is (= (str (:id (room/bundle "insight" source/valid-source))) (:bundle (first authored))))
             (is (= 1 @calls)))))))))

(deftest static-catalog-never-consults-room-hook-even-when-broken
  (@#'host/with-host
   true
   (fn [{:keys [host]}]
     (doseq [room-config [nil {:resolve-read nil}
                          {:resolve-read (fn [& _] (throw (AssertionError. "Static catalog consulted Room hook")))}]]
       (with-redefs [server/current-daemon (constantly (assoc-in host [:config :room-files] room-config))]
         (let [send (@#'host/session "2025-11-25" principal)]
           (is (= (json/read-value (json/write-value-as-string (mapv catalog/describe (vals catalog/workflows)))
                                   json/keyword-keys-object-mapper)
                  (data (tool send "catalog_list" {}))))))))))

(deftest configured-catalog-failures-are-safe-and-current-revocation-precedes-resolver
  (@#'host/with-host
   true
   (fn [{:keys [host owned allowed? calls stamp]}]
     (source/seed! owned "insight" source/valid-source)
     (doseq [bad [nil {:resolve-read nil}
                  {:resolve-read (fn [& _] (throw (ex-info "SECRET-NATIVE-HANDLE" {:secret "SECRET-NATIVE-HANDLE"})))}]]
       (with-redefs [server/current-daemon (constantly (assoc-in host [:config :room-files] bad))]
         (let [reply (tool (@#'host/session "2025-11-25" principal) "catalog_list" {:room "room"})]
           (is (or (:error reply) (get-in reply [:result :isError])))
           (is (not (.contains (pr-str reply) "SECRET-NATIVE-HANDLE"))))))
     (with-redefs [room/tier (fn [& _] (throw (ex-info "SECRET-PROMOTION-PATH" {})))]
       (let [reply (tool (@#'host/session "2025-11-25" principal) "catalog_list" {:room "room"})]
         (is (or (:error reply) (get-in reply [:result :isError])))
         (is (not (.contains (pr-str reply) "SECRET-PROMOTION-PATH")))))
     (reset! allowed? false)
     (let [before (count @calls)
           reply (tool (@#'host/session "2025-11-25" principal) "catalog_list" {:room "room"})]
       (is (or (:error reply) (get-in reply [:result :isError])))
       (is (= before (count @calls)))))))

(deftest unconfigured-catalog-preserves-selected-authored-source
  (@#'host/with-host
   true
   (fn [{:keys [host room owned other calls]}]
     (source/seed! owned "owned" source/valid-source)
     (source/seed! other "selected" (assoc source/valid-source "workflow.edn" "{:title \"Selected\" :task \"Read\"}"))
     (with-redefs [server/current-daemon (constantly (update host :config dissoc :room-files))
                   room/tier (constantly :ad-hoc)]
       (let [send (@#'host/session "2025-11-25" principal)
             authored (filter :bundle (data (tool send "catalog_list" {:room (name (:id room))})))]
         (is (= ["Selected"] (mapv :title authored)))
         (is (every? #(not (contains? % :target-stamp)) authored))
         (is (empty? @calls)))))))
