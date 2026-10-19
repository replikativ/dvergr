(ns dvergr.substrate.workspace-selection-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as discourse]
            [dvergr.intake.bash :as bash]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.workspace :as sandbox-workspace]
            [dvergr.substrate.geschichte :as g]
            [dvergr.substrate.git :as git]
            [dvergr.tools :as tools]
            [geschichte.query :as query]
            [geschichte.repo :as repo]
            [muschel.fs :as fs]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]
            [yggdrasil.protocols :as protocols]))

(defn- register-repository! [id marker]
  (let [cfg (assoc g/repository-options :store {:backend :memory :id (random-uuid)})]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (repo/init! conn {:name id})
        (repo/write! conn "identity.txt" (.getBytes marker "UTF-8"))
        (repo/write! conn "out/selected.txt" (.getBytes "unchanged parent" "UTF-8"))
        (repo/stage-all! conn)
        (repo/commit! conn {:message "Seed workspace identity"})
        (ygg/register! (gy/create conn {:system-name id}))
        conn
        (catch Throwable e (d/release conn) (throw e))))))

(defn- with-workspaces [ids f]
  (let [ctx (sp/create-execution-context) opened (atom [])]
    (try
      (binding [ec/*execution-context* ctx]
        (doseq [id ids]
          (swap! opened conj [id (register-repository! id id)]))
        (f ctx (into {} @opened)))
      (finally
        (binding [ec/*execution-context* ctx]
          (doseq [[id _] @opened] (ygg/unregister! id)))
        (doseq [[_ conn] @opened] (d/release conn))
        (context/stop-context! ctx)))))

(defn- current-id [] (some-> (g/current-system) protocols/system-id str))

(defn- corrupt-selection! [value]
  ;; A nil LEAF update means deletion in Spindel. Corrupt the containing map
  ;; instead, so even nil is genuinely present and cannot enable fallback.
  (ec/swap-state! (pop @#'g/selection-path) #(assoc % (peek @#'g/selection-path) value))
  (is (contains? (ec/get-state (pop @#'g/selection-path)) (peek @#'g/selection-path))))

(deftest exact-selection-is-independent-of-registration-order-or-hashmap-order
  (doseq [ids [["room-repo-attached" "room-repo-owned"]
               ["room-repo-owned" "room-repo-attached"]
               (mapv #(str "room-repo-" %) (range 12))]]
    (with-workspaces ids
      (fn [_ _]
        (is (nil? (g/selected-system-id)))
        (is (some? (g/current-system)) "unselected callers retain legacy resolution")
        (let [selected (last ids)]
          (is (= selected (g/select-workspace! selected)))
          (is (= selected (g/selected-system-id) (current-id)))
          (is (= selected (fs/read-file (g/filesystem) "/identity.txt"))))))))

(deftest invalid-or-missing-selection-never-replaces-a-valid-one
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (g/select-workspace! "room-repo-owned")
      (doseq [id [nil :room-repo-owned "" " " (apply str (repeat 4097 "x")) "absent"]]
        (is (thrown? clojure.lang.ExceptionInfo (g/select-workspace! id)))
        (is (= "room-repo-owned" (current-id)))))))

(deftest removal-of-selected-system-fails-closed-instead-of-falling-back
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (g/select-workspace! "room-repo-owned")
      (ygg/unregister! "room-repo-owned")
      (is (= "room-repo-owned" (g/selected-system-id)))
      (is (thrown? clojure.lang.ExceptionInfo (g/current-system)))
      (is (thrown? clojure.lang.ExceptionInfo (g/current-workspace))))))

(deftest alias-ambiguity-and-a-non-geschichte-selection-are-not-fallback-cases
  (with-workspaces ["room-repo-owned"]
    (fn [_ _]
      (let [system (g/current-system)]
        (with-redefs [ygg/registered-systems (constantly {"one" system "two" system})]
          (is (thrown? clojure.lang.ExceptionInfo (g/select-workspace! "room-repo-owned"))))
        (with-redefs [ygg/registered-systems
                      (constantly {"wrong-type" (reify protocols/SystemIdentity
                                                  (system-id [_] "room-repo-owned")
                                                  (system-type [_] :not-geschichte)
                                                  (capabilities [_] {}))})]
          (is (thrown? clojure.lang.ExceptionInfo (g/select-workspace! "room-repo-owned"))))
        (is (nil? (g/selected-system-id)))))))

(deftest a-corrupt-explicit-marker-is-not-an-unselected-workspace
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (doseq [value [nil false {} ""]]
        (corrupt-selection! value)
        (is (true? (g/workspace-selected?)))
        (is (thrown? clojure.lang.ExceptionInfo (g/current-system)))))))

(deftest selected-errors-cannot-become-native-fallbacks-in-tool-or-sandbox-consumers
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [ctx _]
      (let [sci (sandbox/create-base-ctx)]
        (doseq [value [nil "missing"]]
          (corrupt-selection! value)
          (doseq [resolve-workspace [g/optional-workspace bash/default-workspace sandbox-workspace/workspace-root
                                     #'tools/resolve-default-workspace
                                     #(sandbox/setup-agent-namespaces! sci ctx)]]
            (is (thrown? clojure.lang.ExceptionInfo (resolve-workspace)))))))))

(deftest optional-resolution-preserves-only-the-unselected-legacy-fallback
  (with-workspaces ["room-repo-owned"]
    (fn [_ _]
      (let [failure (ex-info "lookup failed" {})]
        (with-redefs [g/current-workspace (fn [] (throw failure))
                      git/safe-workspace-root (constantly "/isolated-legacy-test")]
          (is (nil? (g/optional-workspace)))
          (is (= "/isolated-legacy-test" (bash/default-workspace)))
          (is (= "/isolated-legacy-test" (str (sandbox-workspace/workspace-root))))
          (is (nil? (#'tools/resolve-default-workspace))))
        (with-redefs [g/current-workspace (fn [] (g/select-workspace! "room-repo-owned") (throw failure))]
          (is (identical? failure (try (g/optional-workspace) nil (catch Throwable e e)))))))))

(deftest unbound-legacy-consumers-retain-their-existing-native-fallback
  (binding [ec/*execution-context* nil]
    (with-redefs [git/safe-workspace-root (constantly "/isolated-unbound-test")]
      (is (nil? (g/optional-workspace)))
      (is (= "/isolated-unbound-test" (bash/default-workspace)))
      (is (= "/isolated-unbound-test" (str (sandbox-workspace/workspace-root))))
      (is (nil? (#'tools/resolve-default-workspace))))))

(deftest inherited-selection-does-not-reacquire-a-parent-system-excluded-from-the-fork
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (g/select-workspace! "room-repo-owned")
      (let [child (ygg/fork! {:systems #{"room-repo-attached"}})]
        (try
          (ygg/with-fork child
            (is (= "room-repo-owned" (g/selected-system-id)))
            (is (thrown? clojure.lang.ExceptionInfo (g/current-system)))
            (is (thrown? clojure.lang.ExceptionInfo (g/select-workspace! "room-repo-owned"))))
          (is (= "room-repo-owned" (current-id)))
          (finally (ygg/discard-fork! child)))))))

(deftest selection-inherits-through-nested-forks-without-entangling-parents
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ conns]
      (g/select-workspace! "room-repo-owned")
      (let [child (ygg/fork!)]
        (try
          (ygg/with-fork child
            (is (= "room-repo-owned" (g/selected-system-id) (current-id)))
            (is (not (identical? (get conns "room-repo-owned") (:conn (g/current-workspace)))))
            (g/select-workspace! "room-repo-attached")
            (let [grandchild (ygg/fork!)]
              (try
                (ygg/with-fork grandchild
                  (is (= "room-repo-attached" (g/selected-system-id) (current-id)))
                  (g/select-workspace! "room-repo-owned")
                  (is (= "room-repo-owned" (current-id)))
                  (fs/write-string! (g/filesystem) "/out/selected.txt" "grandchild" false))
                (finally (ygg/discard-fork! grandchild))))
            (is (= "room-repo-attached" (g/selected-system-id) (current-id))))
          (is (= "room-repo-owned" (g/selected-system-id) (current-id)))
          (is (= "unchanged parent" (fs/read-file (g/filesystem) "/out/selected.txt")))
          (finally (ygg/discard-fork! child))))
      (is (= "room-repo-owned" (g/selected-system-id) (current-id))))))

(deftest substrate-merge-does-not-publish-the-childs-computation-selection
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (g/select-workspace! "room-repo-owned")
      (let [child (ygg/fork!)]
        (ygg/with-fork child
          (g/select-workspace! "room-repo-attached")
          (is (= "room-repo-attached" (current-id))))
        (ygg/merge-fork! child)
        (is (= "room-repo-owned" (g/selected-system-id) (current-id)))))))

(deftest explicit-run-local-selection-and-frozen-forks-do-not-follow-later-parent-choices
  (doseq [mode [:following :frozen]]
    (with-workspaces ["room-repo-attached" "room-repo-owned"]
      (fn [_ _]
        (g/select-workspace! "room-repo-owned")
        (let [child (ygg/fork! {:mode mode})]
          (try
            ;; A following world deliberately follows untouched parent state.
            ;; A bound Run setup writes its own selection before any tools run.
            (when (= :following mode)
              (ygg/with-fork child (g/select-workspace! "room-repo-owned")))
            (g/select-workspace! "room-repo-attached")
            (ygg/with-fork child
              (is (= "room-repo-owned" (g/selected-system-id) (current-id))))
            (is (= "room-repo-attached" (current-id)))
            (finally (ygg/discard-fork! child))))))))

(deftest an-untouched-following-selection-retains-spindels-following-semantics
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (g/select-workspace! "room-repo-owned")
      (let [child (ygg/fork! {:mode :following})]
        (try
          (g/select-workspace! "room-repo-attached")
          (ygg/with-fork child
            (is (= "room-repo-attached" (g/selected-system-id) (current-id))))
          (finally (ygg/discard-fork! child)))))))

(deftest review-diff-compares-the-same-logical-system-despite-a-different-parent-choice
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ conns]
      (let [expected-base (:geschichte.commit/id (repo/head-commit (get conns "room-repo-owned")))
            child (ygg/fork!)]
        (try
          (ygg/with-fork child
            (g/select-workspace! "room-repo-owned")
            (fs/write-string! (g/filesystem) "/out/selected.txt" "only selected child changed" false))
          (is (= "room-repo-attached" (current-id)))
          (let [changes repo/changes seen (atom nil)
                diff (with-redefs [repo/changes (fn [conn base tip]
                                                  (reset! seen base)
                                                  (changes conn base tip))]
                       (g/diff-since-fork (:child-ctx child)))]
            (is (= expected-base @seen))
            (is (= ["out/selected.txt"] (:files diff))))
          (finally (ygg/discard-fork! child)))))))

(deftest explicit-selected-diff-refuses-a-missing-parent-counterpart
  (with-workspaces ["room-repo-attached" "room-repo-owned"]
    (fn [_ _]
      (let [child (ygg/fork!)]
        (try
          (ygg/with-fork child (g/select-workspace! "room-repo-owned"))
          (ygg/unregister! "room-repo-owned")
          (is (thrown? clojure.lang.ExceptionInfo (g/diff-since-fork (:child-ctx child))))
          (finally (ygg/discard-fork! child)))))))

(defn- sci-team []
  (let [child {:kind :sci :source "(ns selected.child)(defn main [_] (slurp \"/identity.txt\"))"
               :entrypoint 'selected.child/main :args [] :timeout-ms 30000}
        parent {:kind :sci
                :source (str "(ns selected.parent)(require '[dvergr.agent :as agent])"
                             "(defn main [_] (let [team (agent/make-agent (agent/roster) '"
                             (pr-str {:id :child :tools #{} :sandbox #{} :program child})
                             ") handle (agent/hire! team :child {:task :child :settlement :discard})"
                             " result @(agent/result-spin handle) marker (:run/value result)]"
                             "(spit \"/out/selected.txt\" marker) (slurp \"/out/selected.txt\")))")
                :entrypoint 'selected.parent/main :args [] :timeout-ms 30000}]
    (roster/make-agent (roster/make-roster) {:id :candidate :tools #{} :sandbox #{} :program parent})))

(deftest async-sci-parent-child-tools-and-capture-use-the-same-selected-cloned-workspace
  (let [room (discourse/make-room {:id (keyword (str "selected-sci-" (random-uuid))) :store (memory/make)})
        opened (atom [])
        selected "room-repo-owned"]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        ;; The wrong attachment is first: no ordering convention may decide.
        (doseq [id ["room-repo-attached" selected]]
          (swap! opened conj [id (register-repository! id id)]))
        (is (= "room-repo-attached" (current-id)))
        (let [setup (evaluation/make-world-setup
                     {:id :test/selected-workspace :version 1 :basis selected
                      :prepare (fn [{world :room}]
                                 (binding [ec/*execution-context* (:ctx world)]
                                   (g/select-workspace! selected)))})
              evaluator (evaluation/make-evaluator
                         {:id :test/selected-capture :version 1 :basis selected
                          :capture (fn [{world :world/room}]
                                     (binding [ec/*execution-context* (:ctx world)]
                                       {:id (current-id) :selected (g/selected-system-id)
                                        :repository (:geschichte.repo/id (query/repository @(:conn (g/current-workspace))))
                                        :text (fs/read-file (g/filesystem) "/out/selected.txt")}))
                          :observe (fn [{:keys [default] evidence :execution/evidence}]
                                     (assoc default :workspace evidence))
                          :verify (fn [_ evidence]
                                    (let [right? (= selected (get-in evidence [:workspace :id])
                                                    (get-in evidence [:workspace :selected])
                                                    (get-in evidence [:workspace :text]))]
                                      {:checks {:selected? right?} :reward (if right? 1.0 0.0)}))})
              ref (evaluation/evaluator-ref evaluator)
              env (environment/make-environment
                   {:id :test/selected-sci :task :work
                    :verifier {:id (:verifier/id ref) :version (:verifier/version ref) :basis (:verifier/basis ref)}
                    :limits {:timeout-ms 60000 :cancel-timeout-ms 5000}
                    :world {:isolation :ctx :settlement :discard :setup (evaluation/world-setup-ref setup)}})
              result @(evaluation/evaluate room (sci-team) :candidate env evaluator
                                           {:world-setup setup
                                            :delegation-policy {:tools #{} :sandboxes #{#{}} :providers #{}
                                                                :program-kinds #{:sci} :max-attempts 2 :max-parallelism 1}})
              certified (episode/attempt room (:run/id result))]
          (is (= :completed (get-in result [:attempt-receipt :attempt/status])))
          (is (= {:selected? true} (get-in result [:attempt-receipt :attempt/checks])))
          (is (= selected (get-in certified [:attempt/evidence :workspace :text])))
          (is (= "room-repo-attached" (current-id)) "Run selection did not mutate the control Room")
          (is (nil? (g/selected-system-id)))
          (doseq [[_ conn] @opened]
            (is (= "unchanged parent" (fs/read-file (g/filesystem {:conn conn :repository {:conn conn :config (:config @conn)}})
                                                    "/out/selected.txt"))))))
      (finally
        (discourse/close-room! room)
        (doseq [[_ conn] @opened] (d/release conn))))))
