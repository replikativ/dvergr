(ns dvergr.repl-meta-harness-test
  "End-to-end contract for the agent-facing REPL meta-harness surface.

   The test deliberately does not call an LLM. It executes the real
   `clojure_eval` tool against the same room-bound SCI and ChatContext used by an
   agent turn. Sandboxed Clojure constructs a nested persistent room, a second
   evaluation proves REPL state survived, and the host verifies the live room
   registry and durable Datahike row."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.turn :as turn]
            [dvergr.discourse.definitions :as definitions]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.room.registry :as room-registry]
            [dvergr.room.store :as room-store]
            [dvergr.rooms :as rooms]
            [dvergr.substrate.config :as config]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as system-db]
            [dvergr.system.rooms :as system-rooms]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- require-room! [execution-ctx slug]
  (or (binding [ec/*execution-context* execution-ctx]
        (room-registry/lookup slug))
      (throw (ex-info "Contract parent room is not registered"
                      {:parent-slug slug}))))

(defn- eval! [room tool-ctx code]
  (binding [ec/*execution-context* (:ctx room)]
    (tools/execute "clojure_eval" {:code code} tool-ctx)))

(defn run-against-daemon!
  "Exercise the REPL contract against `daemon` without calling a provider.

   The child is retained for inspection. Pass a unique `:child-slug` when using
   this deliberately against a live development daemon."
  ([daemon]
   (run-against-daemon! daemon {}))
  ([daemon {:keys [parent-slug child-slug title]
            :or {parent-slug "boardroom"}}]
   (let [execution-ctx (:execution-ctx daemon)
         child-slug   (or child-slug (str "repl-contract-" (random-uuid)))
         title        (or title (str "REPL contract " child-slug))
         parent       (require-room! execution-ctx parent-slug)
         child-id     (room-store/slug->room-id child-slug)]
     (when (binding [ec/*execution-context* execution-ctx]
             (room-registry/lookup child-id))
       (throw (ex-info "Contract child room already exists"
                       {:child-slug child-slug})))
     (let [parent-row (binding [ec/*execution-context* execution-ctx]
                        (system-db/room-by-slug parent-slug))
           parent-uuid (:room/id parent-row)
           chat-ctx
           (turn/new-working-ctx
            {:execution-ctx (:ctx parent)
             :title         (str "repl-meta-harness-contract-" child-slug)
             :db-conn       (some-> parent :store :conn)
             :kb-conn       (binding [ec/*execution-context* (:ctx parent)]
                              (system-rooms/room-kb-conn parent-uuid))
             :room-id       parent-uuid
             :durable?      false})
           tool-ctx
           (binding [ec/*execution-context* (:ctx parent)]
             (tools/make-context
              {:sci-ctx       (:sci-ctx chat-ctx)
               :chat-ctx      chat-ctx
               :execution-ctx (:ctx parent)
               :isolation     :sci
               :tools         {"clojure_eval" (tools/get-tool "clojure_eval")}}))
           create-code
           (str "(require '[dvergr.room :as room]) "
                "(def contract-child "
                "(room/create! {:slug " (pr-str child-slug)
                " :title " (pr-str title) " :agents #{}})) "
                "{:child (select-keys contract-child [:slug :title :agents])}")
           create-result (eval! parent tool-ctx create-code)
           inspect-code
           (str "{:session-child-slug (:slug contract-child) "
                ":visible? (some? (dvergr.room/get " (pr-str child-slug) ")) "
                ":children (set (map :slug "
                "(dvergr.room/children " (pr-str parent-slug) ")))}")
           inspect-result (eval! parent tool-ctx inspect-code)
           create-value (get-in create-result [:metadata :value])
           inspect-value (get-in inspect-result [:metadata :value])
           live-child (binding [ec/*execution-context* execution-ctx]
                        (room-registry/lookup child-id))
           durable-child (binding [ec/*execution-context* execution-ctx]
                           (rooms/get-room-by-slug child-slug))
           checks
           {:create-tool-succeeded (= :success (:type create-result))
            :inspect-tool-succeeded (= :success (:type inspect-result))
            :sandbox-created-child  (= child-slug (get-in create-value [:child :slug]))
            :repl-state-persisted   (= child-slug (:session-child-slug inspect-value))
            :sandbox-sees-child     (true? (:visible? inspect-value))
            :sandbox-sees-nesting   (contains? (:children inspect-value) child-slug)
            :host-sees-child        (= child-id (:id live-child))
            :host-sees-nesting      (= (room-store/slug->room-id parent-slug)
                                       (:parent-id live-child))
            :datahike-sees-child    (= child-slug (:room/slug durable-child))
            :datahike-sees-nesting  (= parent-slug (:room/parent-slug durable-child))}]
       {:passed? (every? true? (vals checks))
        :checks checks
        :parent-slug parent-slug
        :child-slug child-slug
        :create-result create-result
        :inspect-result inspect-result
        :live-room (select-keys live-child [:id :slug :title :parent-id])
        :durable-room (select-keys durable-child
                                   [:room/id :room/slug :room/name :room/parent-slug])}))))

(defn run-contract!
  "Run the contract in an isolated, provider-free daemon.

   Public so a developer can invoke it directly at a REPL. The daemon is always
   stopped and dvergr's original state root is restored."
  []
  (when @daemon/current-daemon
    (throw (ex-info "Stop the current daemon before running the isolated contract"
                    {})))
  (let [original-home (paths/home)
        contract-home (str (System/getProperty "java.io.tmpdir")
                           "/dvergr-repl-contract-" (random-uuid))
        instance (atom nil)]
    (try
      (paths/set-home! contract-home)
      (system-db/reset-conn!)
      ;; No file-autostarted agent means no provider discovery. Empty config also
      ;; keeps local secrets and user configuration out of this deterministic run.
      (with-redefs [definitions/autostart-agents (constantly {})
                    config/config (constantly {})
                    config/secret-specs (constantly [])
                    config/sandbox-env (constantly {})]
        (let [d (daemon/start! {:agents {}
                                :db-path (str contract-home "/daemon-db")
                                :gc {:interval-ms 3600000}})]
          (reset! instance d)
          (assoc (run-against-daemon!
                  d {:child-slug "nested-room-contract"
                     :title "Nested room contract"})
                 :state-root contract-home)))
      (finally
        (when-let [d @instance]
          (try (daemon/stop! d) (catch Throwable _)))
        (system-db/reset-conn!)
        (paths/set-home! original-home)))))

(deftest agent-facing-repl-constructs-a-durable-nested-room
  (let [result (run-contract!)]
    (testing "every layer observes the room created from clojure_eval"
      (is (:passed? result) (pr-str (:checks result)))
      (is (every? true? (vals (:checks result)))))))
