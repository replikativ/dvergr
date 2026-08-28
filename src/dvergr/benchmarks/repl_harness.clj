(ns dvergr.benchmarks.repl-harness
  "Deterministic checks of the agent-facing meta-harness programming surface.

   This namespace deliberately does not call an LLM. `run!` executes the real
   `clojure_eval` tool against the same room-bound SCI and ChatContext used by an
   agent turn. The sandboxed program constructs a nested persistent room, then a
   second evaluation proves that both REPL definitions and room state survived.
   Finally the host checks the live registry and durable system database.

   The benchmark creates a real room. Use an isolated daemon for repeatable runs,
   or pass a unique `:child-slug` when deliberately exercising a live daemon."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.agent.turn :as turn]
            [dvergr.room.registry :as room-registry]
            [dvergr.room.store :as room-store]
            [dvergr.rooms :as rooms]
            [dvergr.system.db :as system-db]
            [dvergr.system.rooms :as system-rooms]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- elapsed-ms [start-ns end-ns]
  (/ (double (- end-ns start-ns)) 1000000.0))

(defn- require-room! [execution-ctx slug]
  (or (binding [ec/*execution-context* execution-ctx]
        (room-registry/lookup slug))
      (throw (ex-info "Benchmark parent room is not registered"
                      {:parent-slug slug}))))

(defn- eval! [room tool-ctx code]
  (binding [ec/*execution-context* (:ctx room)]
    (tools/execute "clojure_eval" {:code code} tool-ctx)))

(defn run!
  "Exercise the production REPL-to-meta-harness path against `daemon`.

   Options:
   - `:parent-slug` - existing parent room (default `\"boardroom\"`)
   - `:child-slug`  - new room slug; must not already exist
   - `:title`       - child title

   Returns timings, both tool results, observable state, and a `:passed?` flag.
   No model provider is resolved or invoked. The child is retained so callers
   can inspect durability and UI visibility after the run."
  ([daemon]
   (run! daemon {}))
  ([daemon {:keys [parent-slug child-slug title]
            :or {parent-slug "boardroom"}}]
   (let [execution-ctx (:execution-ctx daemon)
         child-slug   (or child-slug (str "repl-benchmark-" (random-uuid)))
         title        (or title (str "REPL benchmark " child-slug))
         parent       (require-room! execution-ctx parent-slug)
         child-id     (room-store/slug->room-id child-slug)]
     (when (binding [ec/*execution-context* execution-ctx]
             (room-registry/lookup child-id))
       (throw (ex-info "Benchmark child room already exists"
                       {:child-slug child-slug})))
     (let [parent-row (binding [ec/*execution-context* execution-ctx]
                        (system-db/room-by-slug parent-slug))
           parent-uuid (:room/id parent-row)
           t0 (System/nanoTime)
           chat-ctx
           (turn/new-working-ctx
            {:execution-ctx (:ctx parent)
             :title         (str "repl-harness-benchmark-" child-slug)
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
           t1 (System/nanoTime)
           create-code
           (str "(require '[dvergr.room :as room]) "
                "(def benchmark-child "
                "(room/create! {:slug " (pr-str child-slug)
                " :title " (pr-str title) " :agents #{}})) "
                "{:child (select-keys benchmark-child [:slug :title :agents])}")
           create-result (eval! parent tool-ctx create-code)
           t2 (System/nanoTime)
           inspect-code
           (str "{:session-child-slug (:slug benchmark-child) "
                ":visible? (some? (dvergr.room/get " (pr-str child-slug) ")) "
                ":children (set (map :slug "
                "(dvergr.room/children " (pr-str parent-slug) ")))}")
           inspect-result (eval! parent tool-ctx inspect-code)
           t3 (System/nanoTime)
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
            :datahike-sees-nesting  (= parent-slug (:room/parent-slug durable-child))}
           t4 (System/nanoTime)]
       {:passed? (every? true? (vals checks))
        :checks checks
        :timings-ms {:sandbox-setup (elapsed-ms t0 t1)
                     :create        (elapsed-ms t1 t2)
                     :inspect       (elapsed-ms t2 t3)
                     :host-verify   (elapsed-ms t3 t4)
                     :total         (elapsed-ms t0 t4)}
        :parent-slug parent-slug
        :child-slug child-slug
        :create-result create-result
        :inspect-result inspect-result
        :live-room (select-keys live-child [:id :slug :title :parent-id])
        :durable-room (select-keys durable-child
                                   [:room/id :room/slug :room/name :room/parent-slug])}))))
