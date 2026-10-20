(ns dvergr.rooms.targeted-files-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.publication :as publication]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]
            [yggdrasil.protocols :as protocols]))

(def ^:private owned-id "room-repo-owned")
(def ^:private other-id "room-repo-other")

(defrecord ConnectionAlias [conn]
  protocols/SystemIdentity
  (system-id [_] "datahike-conn-alias")
  (system-type [_] :datahike)
  (capabilities [_] {}))

(defn- repository! [id]
  (let [cfg (assoc gs/repository-options :store {:backend :memory :id (random-uuid)})]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (repo/init! conn {:name id})
      (repo/write! conn "identity.txt" (.getBytes id "UTF-8"))
      (repo/stage-all! conn)
      (repo/commit! conn {:message "Seed exact workspace"})
      {:cfg cfg :conn conn :system (gy/create conn {:system-name id})})))

(defn- with-repositories [ids f]
  (let [room (d/make-room {:id (keyword (str "targeted-files-" (random-uuid)))})
        opened (atom [])]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (doseq [id ids]
          (let [repository (repository! id)]
            (swap! opened conj [id repository])
            (ygg/register! (:system repository))))
        (gs/select-workspace! other-id)
        (f room (into {} @opened)))
      (finally
        (let [shutdown (d/close-registered-rooms! (:ctx room))]
          (try
            (doseq [[_ {:keys [conn cfg]}] @opened]
              (dh/release conn)
              (dh/delete-database cfg))
            (finally (d/close-shutdown-contexts! shutdown))))))))

(defn- snapshot [conn]
  {:head (some-> (repo/head-commit conn) :geschichte.commit/id)
   :basis (dh/commit-id @conn)
   :worktree (repo/worktree conn)
   :index (repo/index-tree conn)})

(defn- selection [] (ec/get-state [:dvergr :workspace]))

(defn- error-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(defn- publication-options [conn journal]
  (let [plan (repo/prepare-commit conn {:message "Identify explicit publication target"})]
    {:identity {:publication/id (random-uuid) :publication/intent (random-uuid)
                :publication/actor (random-uuid) :publication/manifest (random-uuid)
                :publication/repository (:repository plan)
                :publication/store (get-in plan [:workspace :store :id])
                :publication/backend (get-in plan [:workspace :store :backend])
                :publication/branch (get-in plan [:workspace :branch])
                :publication/ref (:ref plan)}
     :authorize! (constantly true)
     :before-dispatch! #(swap! journal conj %)}))

(deftest explicit-write-retains-one-descriptor-and-never-changes-selection
  (doseq [ids [[owned-id other-id] [other-id owned-id]]]
    (with-repositories ids
      (fn [room repositories]
        (let [{:keys [system conn]} (get repositories owned-id)
              other-conn (:conn (get repositories other-id))
              other-before (snapshot other-conn)
              owned-before (:head (snapshot conn))
              selected-before (selection)
              first-descriptor (atom nil)
              committed-descriptor (atom nil)
              resolve! gs/workspace-for-system!
              commit! forks/commit-paths-in-workspace!
              result (with-redefs [gs/workspace-for-system!
                                   (fn [target]
                                     (let [workspace (resolve! target)]
                                       (when (identical? target system)
                                         (compare-and-set! first-descriptor nil workspace))
                                       workspace))
                                   forks/commit-paths-in-workspace!
                                   (fn [target workspace expected message opts]
                                     (reset! committed-descriptor workspace)
                                     (commit! target workspace expected message opts))]
                       (files/write-to! room system {"out/wiki/brief.md" {:text "# Accepted insight"}}
                                        {:message "Publish explicit target"
                                         :expected-worktree {"/out/wiki/brief.md" nil}}))]
          (is (:applied? result))
          (is (some? (:commit result)))
          (is (identical? @first-descriptor @committed-descriptor))
          (is (not= owned-before (:head (snapshot conn))))
          (is (= "# Accepted insight"
                 (fs/read-file (gs/filesystem (resolve! system)) "/out/wiki/brief.md")))
          (is (= other-before (snapshot other-conn)))
          (is (= selected-before (selection)))
          (is (= other-id (gs/selected-system-id))))))))

(deftest legacy-write-resolves-once-even-when-a-handler-changes-selection
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (gs/select-workspace! owned-id)
      (let [other-conn (:conn (get repositories other-id))
            other-before (snapshot other-conn)
            result (files/write! room {"out/legacy.md" {:text "same captured target"}}
                                 {:boundary (constantly
                                             {:handlers [(fn [effect next]
                                                           (when (= :git/commit (:effect effect))
                                                             (gs/select-workspace! other-id))
                                                           (next effect))]})})]
        (is (:applied? result))
        (is (= other-before (snapshot other-conn)))
        (is (contains? (repo/worktree (:conn (get repositories owned-id))) "out/legacy.md"))
        (is (= other-id (gs/selected-system-id)))))))

(deftest missing-replaced-duplicate-and-wrong-type-targets-refuse-before-mutation
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            before (into {} (map (fn [[id repository]] [id (snapshot (:conn repository))])) repositories)
            selected-before (selection)
            attempt #(files/write-to! room % {"out/refused.md" {:text "must not write"}} {})
            unchanged! #(do
                          (is (= before (into {} (map (fn [[id repository]] [id (snapshot (:conn repository))])) repositories)))
                          (is (= selected-before (selection))))]
        (ygg/unregister! owned-id)
        (is (= ::gs/workspace-unavailable (error-type #(attempt system))))
        (unchanged!)
        (ygg/register! (gy/create conn {:system-name owned-id}))
        (is (= ::gs/workspace-unavailable (error-type #(attempt system))))
        (unchanged!)
        (ygg/unregister! owned-id)
        (ygg/register! system)
        (with-redefs [ygg/registered-systems (constantly {"first" system "alias" system})]
          (is (= ::gs/workspace-unavailable (error-type #(attempt system)))))
        (unchanged!)
        ;; A different logical identity over this native connection would make
        ;; connection-system-id's policy lookup ambiguous, even with a unique id.
        (ygg/register! (gy/create conn {:system-name "same-conn-alias"}))
        (is (= ::gs/workspace-unavailable (error-type #(attempt system))))
        (unchanged!)
        (ygg/unregister! "same-conn-alias")
        (let [registered (ygg/registered-systems)]
          (with-redefs [ygg/registered-systems
                        (constantly (assoc registered "datahike-conn-alias" (->ConnectionAlias conn)))]
            (is (= ::gs/workspace-unavailable (error-type #(attempt system))))))
        (unchanged!)
        (let [wrong (reify protocols/SystemIdentity
                      (system-id [_] "wrong-type")
                      (system-type [_] :not-geschichte)
                      (capabilities [_] {}))]
          (with-redefs [ygg/registered-systems (constantly {"wrong-type" wrong})]
            (is (= ::gs/invalid-workspace-system (error-type #(attempt wrong))))))
        (unchanged!)))))

(deftest outer-effect-registration-change-is-refused-before-file-or-index-write
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            before (snapshot conn)
            selected-before (selection)]
        (is (= ::gs/workspace-unavailable
               (error-type
                #(files/write-to! room system {"out/late-refusal.md" {:text "no mutation"}}
                                  {:boundary (constantly
                                              {:handlers [(fn [effect next]
                                                            (when (= :git/commit (:effect effect))
                                                              (ygg/unregister! owned-id))
                                                            (next effect))]})}))))
        (is (= before (snapshot conn)) "even an unnecessary compensating transaction is absent")
        (is (= selected-before (selection)))
        (ygg/register! system)))))

(deftest inner-effect-registration-mutation-is-compensated-not-a-lifetime-lease
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            before (snapshot conn)
            other-conn (:conn (get repositories other-id))
            other-before (snapshot other-conn)
            selected-before (selection)]
        (try
          (is (= ::gs/workspace-unavailable
                 (error-type
                  #(files/write-to! room system {"out/inner-revocation.md" {:text "temporary"}}
                                    {:boundary (constantly
                                                {:handlers [(fn [effect next]
                                                              (when (= :fs/write (:effect effect))
                                                                (ygg/unregister! owned-id))
                                                              (next effect))]})}))))
          (is (= (dissoc before :basis) (dissoc (snapshot conn) :basis))
              "the retained target is restored, but rollback may transact")
          (is (= other-before (snapshot other-conn)))
          (is (= selected-before (selection)))
          (finally (ygg/register! system)))))))

(deftest public-explicit-commit-rejects-substituted-descriptors-before-staging
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            other-conn (:conn (get repositories other-id))
            workspace (gs/workspace-for-system! system)
            before (snapshot conn)
            other-before (snapshot other-conn)
            selected-before (selection)]
        (doseq [invalid [(assoc workspace :conn other-conn)
                         (assoc-in workspace [:repository :conn] other-conn)
                         (assoc-in workspace [:repository :config :store :id] (random-uuid))
                         (assoc-in workspace [::gs/physical-target :ref] "different-ref")
                         (dissoc workspace :system)]]
          (is (some? (error-type #(forks/commit-paths-in-workspace!
                                   room invalid {"/absent.md" nil} "Refuse descriptor" {}))))
          (is (= before (snapshot conn)))
          (is (= other-before (snapshot other-conn))))
        (is (= selected-before (selection)))))))

(deftest retained-target-accepts-head-advance-but-not-a-ref-switch
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            workspace (gs/workspace-for-system! system)]
        (repo/write! conn "human.md" (.getBytes "unrelated newer HEAD" "UTF-8"))
        (repo/stage-all! conn)
        (repo/commit! conn {:message "Unrelated human commit"})
        (is (identical? workspace (gs/assert-workspace-current! workspace)))
        (repo/branch! conn "other-ref")
        (repo/checkout! conn "other-ref")
        (let [before (snapshot conn)]
          (is (= ::gs/workspace-changed
                 (error-type #(forks/commit-paths-in-workspace!
                               room workspace {"/absent.md" nil} "Wrong ref" {}))))
          (is (= before (snapshot conn))))))))

(deftest parent-workspace-object-and-descriptor-are-not-child-capabilities
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            workspace (gs/workspace-for-system! system)
            before (snapshot conn)
            fork (d/fork-room room {:isolation :ctx})]
        (try
          (is (= ::gs/workspace-unavailable
                 (error-type #(files/write-to! fork system {"out/parent.md" {:text "refuse parent capability"}} {}))))
          (is (= ::gs/workspace-unavailable
                 (error-type #(forks/commit-paths-in-workspace!
                               fork workspace {"/absent.md" nil} "Refuse parent descriptor" {}))))
          (is (= before (snapshot conn)))
          (is (= other-id (gs/selected-system-id)))
          (finally (d/discard fork)))))))

(deftest explicit-publication-recovers-lost-ack-only-in-its-owned-target
  (with-repositories [other-id owned-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            other-conn (:conn (get repositories other-id))
            other-before (snapshot other-conn)
            selected-before (selection)
            journal (atom [])
            options (publication-options conn journal)
            publish! publication/publish!
            error (with-redefs [publication/publish!
                                (fn [& args]
                                  (apply publish! args)
                                  (throw (ex-info "lost after explicit publication"
                                                  {::publication/uncertain? true})))]
                    (try (files/write-to! room system {"out/wiki/result.md" {:text "accepted"}}
                                          {:publication options
                                           :expected-worktree {"/out/wiki/result.md" nil}})
                         nil (catch Exception error error)))
            committed (:head (snapshot conn))]
        (is (publication/uncertain? error))
        (is (= 1 (count @journal)))
        (files/write-to! room system {"out/wiki/result.md" {:text "later human edit"}} {})
        (let [before (snapshot conn)
              recovered (files/write-to! room system {"out/wiki/result.md" {:text "accepted"}}
                                         {:publication options
                                          :expected-worktree {"/out/wiki/result.md" nil}})]
          (is (:recovered? recovered))
          (is (:applied? recovered))
          (is (= (str committed) (:commit recovered)))
          (is (= before (snapshot conn)))
          (is (= "later human edit"
                 (fs/read-file (gs/filesystem (gs/workspace-for-system! system)) "/out/wiki/result.md"))))
        (is (= other-before (snapshot other-conn)))
        (is (= selected-before (selection)))
        (is (= 1 (count @journal)))))))

(deftest explicit-target-does-not-bypass-world-or-current-publication-permission
  (with-repositories [owned-id other-id]
    (fn [room repositories]
      (let [{:keys [system conn]} (get repositories owned-id)
            before (snapshot conn)
            selected-before (selection)
            journal (atom [])]
        (doseq [answer [false nil]]
          (let [options (assoc (publication-options conn journal) :authorize! (constantly answer))]
            (is (some? (error-type #(files/write-to! room system {"out/denied.md" {:text "no"}}
                                                     {:publication options}))))
            (is (= before (snapshot conn)))))
        (effects/install-world! (:ctx room) [[:read-only]])
        (is (= :effect/denied
               (error-type #(files/write-to! room system {"out/denied.md" {:text "no"}} {}))))
        (is (= before (snapshot conn)))
        (is (= selected-before (selection)))
        (is (empty? @journal))))))
