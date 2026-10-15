(ns dvergr.principal-test
  "One principal, one authorization check: every op and every registry tool
   declares what it acts on, `ops/invoke` asks the authorizer before it acts,
   a listing shows only what the caller may read, a post never names someone
   else, and a host can add ops and seams without a daemon."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms :as rooms]
            [dvergr.orchestration.daemon]
            [dvergr.discourse.commands]
            [dvergr.system.db]
            [dvergr.substrate.paths]
            [dvergr.security.allowlist :as allowlist]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]))

(def alice {:principal/id "party/alice" :principal/kind :human :principal/scopes #{:dvergr/admin}})
(def bob {:principal/id "party/bob" :principal/kind :human :principal/scopes #{:dvergr/admin}})

;; ---------------------------------------------------------------------------
;; Coverage: what every op and every registry tool acts on

(def ^:private resource-kinds #{:room :agent :job :global})

(deftest every-op-declares-what-it-acts-on
  (doseq [[op {:keys [authz schema]}] (ops/operations)]
    (let [decl (ops/authz-decl op)
          room? (some #(and (vector? %) (= :room (first %))) (rest schema))]
      (testing (str op)
        (is (some? decl) "an :authz, or a :room to derive it from")
        (is (or room? (:resource authz)) "an op without a room declares its resource")
        (is (fn? (:resource decl)))
        (let [action (:action decl)
              a (if (fn? action) (action {}) action)]
          (is (contains? principal/actions a)))
        (is (contains? resource-kinds (first ((:resource decl) {:room "r" :id "i" :job "j"}))))))))

(def ^:private resource-like-keys
  "Argument names that point at something besides the room: an op taking one
   says explicitly what it acts on."
  #{:agent :agents :workflow :source :file :id :fork :parent :job :experiment :scorecard})

(deftest writes-and-ops-naming-other-resources-declare-explicitly
  (doseq [[op {:keys [kind authz schema]}] (ops/operations)]
    (when (= :write kind)
      (is (:action authz) (str op ": a write declares its :authz")))
    (let [ks (set (keep #(when (vector? %) (first %)) (rest schema)))]
      (when (seq (clojure.set/intersection ks resource-like-keys))
        (is (some? authz) (str op " takes " (clojure.set/intersection ks resource-like-keys)
                               ": it declares what it acts on"))))))

(deftest every-registry-tool-is-classified
  ;; a new tool must say what it does to its room: an effect class, or an
  ;; entry in tools/tool-authz (unclassified falls back to :admin, and fails here)
  (doseq [[tname tool] @tools/registry]
    (is (or (contains? tools/tool-authz tname) (some? (tools/effect-classification tool)))
        (str tname " has no classification"))))

(deftest acting-on-a-room-is-never-global
  (testing "an op whose schema names a required room acts on that room"
    (doseq [[op _] (ops/operations)
            :let [entry (some #(when (and (vector? %) (= :room (first %))) %)
                              (rest (ops/malli-schema op)))]
            :when (and entry (not (and (map? (second entry)) (:optional (second entry)))))
            ;; these two create the room their `room` names: there is none to act on yet
            :when (not (#{:room/import :catalog/promote} op))]
      (is (= [:room "r"] ((:resource (ops/authz-decl op)) {:room "r"})) (str op)))))

(deftest every-registry-tool-declares-its-action
  (doseq [[tname tool] @tools/registry]
    (is (contains? principal/actions (tools/tool-action tname tool {})) tname))
  (testing "the REPL and what evaluates code are the room's whole capability"
    (is (= :admin (tools/tool-action "clojure_eval" (get @tools/registry "clojure_eval") {:code "1"})))
    (is (= :admin (tools/tool-action "shell" (get @tools/registry "shell") {:command "ls"}))))
  (testing "a read-only tool reads; an unclassified one is admin (fail closed)"
    (is (= :read (tools/tool-action "read_file" (get @tools/registry "read_file") {:path "/x"})))
    (is (= :admin (tools/tool-action "nobody_knows" {} {}))))
  (testing "a tool call's request names the room it acts on"
    (is (= {:op :tool/read_file :action :read :resource [:room "r"] :resolved {:room "r"}}
           (dissoc (tools/authz-request "read_file" nil {:path "/x"} {:id :r}) :args)))))

;; ---------------------------------------------------------------------------
;; A daemon of two rooms, with a test authorizer

(def ^:dynamic *rooms* nil)

(defn- daemon [authorize]
  {:execution-ctx (:ctx (first *rooms*)) :config {:auth {:authorize authorize}}})

(use-fixtures :each
  (fn [f]
    (let [c (ctx/create-execution-context)
          rooms [(d/make-room {:id :authz-open :ctx c :store (memory/make)})
                 (d/make-room {:id :authz-closed :ctx c :store (memory/make)})]]
      (try
        (binding [ec/*execution-context* c]
          (run! rreg/register! rooms))
        (binding [*rooms* rooms] (f))
        (finally
          (binding [ec/*execution-context* c]
            (doseq [r rooms]
              (try (rreg/unregister! (:id r)) (catch Throwable _ nil))
              (d/close-room! r))))))))

(defn- only-open
  "Anyone may list; only Alice reads and writes a room, the open one."
  [p {:keys [resolved resource]}]
  (or (= [:global] resource)
      (and (= "party/alice" (:principal/id p)) (= {:room "authz-open"} resolved))))

(deftest a-denied-call-never-runs
  (let [dmn (daemon only-open)
        ran (atom 0)]
    (with-redefs [d/post! (fn [& _] (swap! ran inc))]
      (testing "the authorizer sees the op, the action and the resolved room"
        (let [seen (atom nil)]
          (ops/invoke {:execution-ctx (:execution-ctx dmn)
                       :config {:auth {:authorize (fn [_ r] (reset! seen r) true)}}}
                      alice :room/post {:room "authz-open" :text "hi"})
          (is (= {:op :room/post :action :write :resource [:room "authz-open"]
                  :resolved {:room "authz-open"}}
                 (dissoc @seen :args)))))
      (reset! ran 0)
      (let [e (try (ops/invoke dmn alice :room/post {:room "authz-closed" :text "hi"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= 403 (ops/error-status e)))
        (is (re-find #"Not authorized" (ex-message e)))
        (is (zero? @ran) "nothing was posted"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ops/invoke dmn bob :room/post {:room "authz-open" :text "hi"})))
      (testing "the local principal is the process: never asked"
        (ops/invoke (daemon (constantly false)) principal/local-principal
                    :room/post {:room "authz-closed" :text "hi"})
        (is (= 1 @ran))))))

(deftest there-is-no-default-principal
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a principal"
                        (ops/invoke (daemon only-open) nil :room/list {}))))

(deftest a-resolution-that-throws-denies
  (with-redefs [jobs/owner (fn [_] (throw (ex-info "store gone" {})))]
    (let [e (try (ops/invoke (daemon (constantly true)) alice :job/status {:job (str (random-uuid))}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= 403 (ops/error-status e)) "fail closed, even under an authorizer that allows all"))))

(deftest a-job-is-read-as-its-owner-and-room
  (let [job (str (random-uuid))
        seen (atom nil)]
    (with-redefs [jobs/owner (fn [_] {:room (first *rooms*) :owner :party/alice})
                  jobs/status (fn [& _] nil)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ops/invoke (daemon (fn [_ r] (reset! seen r) false)) bob :job/status {:job job})))
      (is (= {:room "authz-open" :owner :party/alice} (:resolved @seen))))))

(deftest room-list-shows-what-the-caller-may-read
  (let [dmn (daemon only-open)
        ids #(set (map :id %))]
    (is (= #{"authz-open"} (ids (ops/invoke dmn alice :room/list {}))))
    (is (= #{} (ids (ops/invoke dmn bob :room/list {}))) "bob reads no room")
    (is (= #{"authz-open" "authz-closed"}
           (ids (ops/invoke dmn principal/local-principal :room/list {})))
        "the local principal sees every room, as before")))

;; ---------------------------------------------------------------------------
;; No impersonation

(deftest a-principal-posts-as-itself
  (let [dmn (daemon (constantly true))
        posted (atom [])]
    (with-redefs [d/post! (fn [_ m] (swap! posted conj (:from m)))]
      (ops/invoke dmn alice :room/post {:room "authz-open" :text "hi"})
      (is (= [:party/alice] @posted) "the actor of its id")
      (let [e (try (ops/invoke dmn alice :room/post {:room "authz-open" :text "hi" :as "party/bob"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= 403 (ops/error-status e)) "naming another poster is refused")
        (is (= 1 (count @posted))))
      (testing "the local principal still chooses, defaulting to :mcp"
        (ops/invoke dmn principal/local-principal :room/post {:room "authz-open" :text "hi" :as "local"})
        (ops/invoke dmn principal/local-principal :room/post {:room "authz-open" :text "hi"})
        (is (= [:party/alice :local :mcp] @posted))))))

;; ---------------------------------------------------------------------------
;; A host adds ops and seams, without a daemon

(deftest a-host-registers-an-op-and-its-seams
  (try
    (testing "an op must say what it acts on"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares no :authz"
                            (ops/register-op! :host/ping {:doc "ping" :kind :read :schema [:map]
                                                          :impl (fn [_ _] :pong)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dvergr's own"
                            (ops/register-op! :room/list {:doc "x" :kind :read :schema [:map]
                                                          :impl (fn [_ _] nil)}))))
    (ops/register-op! :host/ping {:doc "ping" :kind :read :schema [:map [:room :string]]
                                  :impl (fn [_ {:keys [room]}] {:pong room})})
    (is (contains? (ops/operations) :host/ping))
    (principal/configure! {:authorize only-open})
    (let [host {:execution-ctx (:execution-ctx (daemon nil))}]
      (is (= {:pong "authz-open"} (ops/invoke host alice :host/ping {:room "authz-open"})))
      (is (thrown? clojure.lang.ExceptionInfo (ops/invoke host alice :host/ping {:room "authz-closed"}))
          "the process-wide authorizer decides when there is no daemon config"))
    (finally
      (principal/configure! {})
      (ops/unregister-op! :host/ping))))

;; ---------------------------------------------------------------------------
;; Authentication defaults

(deftest the-default-authenticator-admits-local-access-only
  (let [auth principal/default-authenticate]
    (is (principal/local? (auth {:transport :mcp-stdio})))
    (is (principal/local? (auth {:transport :mcp-tcp :remote-addr "127.0.0.1"})))
    (is (nil? (auth {:transport :mcp-tcp :remote-addr "10.1.2.3"})))
    (is (principal/local? (auth {:transport :web :remote-addr "/0:0:0:0:0:0:0:1"})))
    (is (nil? (auth {:transport :web :remote-addr nil})) "an unknown peer is no local one")
    (is (principal/local? (auth {:transport :mcp-http :bearer "t" :local-token "t" :remote-addr "127.0.0.1"})))
    (is (nil? (auth {:transport :mcp-http :bearer "t" :local-token "t" :remote-addr "10.1.2.3"}))
        "the token is local access only from a local peer")
    (is (nil? (auth {:transport :mcp-http :bearer "x" :local-token "t" :remote-addr "127.0.0.1"})))
    (is (nil? (auth {:transport :mcp-http :bearer nil :local-token "t" :remote-addr "127.0.0.1"})))
    (testing "a forwarding header is never local: a proxy on this host makes every peer loopback"
      (doseq [h ["x-forwarded-for" "forwarded" "x-real-ip"]]
        (is (nil? (auth {:transport :web :remote-addr "127.0.0.1" :headers {h "127.0.0.1"}})) h)
        (is (nil? (auth {:transport :mcp-http :bearer "t" :local-token "t" :remote-addr "127.0.0.1"
                         :headers {h "127.0.0.1"}})) h)))
    (testing "the default refuses a non-loopback listener"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs an authenticator"
                            (principal/check-bind! "x" "0.0.0.0" principal/default-authenticate)))
      (is (nil? (principal/check-bind! "x" "0.0.0.0" (fn [_] nil))))
      (is (nil? (principal/check-bind! "x" "127.0.0.1" principal/default-authenticate))))))

(deftest local-is-an-identity-not-a-claim
  (let [forged {:principal/id :local :principal/kind :local :principal/scopes #{:dvergr/admin}}]
    (is (principal/local? principal/local-principal))
    (is (principal/local? (assoc principal/local-principal :principal/claims {:x 1})) "it survives assoc")
    (is (not (principal/local? forged)))
    (is (not (principal/local? (select-keys principal/local-principal
                                            [:principal/id :principal/kind :principal/scopes])))
        "nor a copy of its keys")
    (let [e (try (ops/invoke (daemon (constantly true)) forged :room/list {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= 403 (ops/error-status e)) "invoke refuses a principal claiming :local"))))

(deftest a-telegram-sender-is-local-only-on-a-closed-allowlist
  (let [user {:id 42 :username "alice"}
        auth #(principal/default-authenticate {:transport :telegram :telegram/user %})]
    (try
      (allowlist/set-strict! false)
      (allowlist/set-users! [])
      (is (= "telegram/42" (:principal/id (auth user))) "an open allowlist: a principal, never local")
      (is (not (principal/local? (auth user))))
      (allowlist/set-users! [42])
      (is (principal/local? (auth user)) "listed on a non-empty allowlist: the operator")
      (is (nil? (auth {:id 7})) "not listed: nobody")
      (finally (allowlist/set-users! []) (allowlist/set-strict! false)))))

(deftest an-agents-tool-call-is-authorized-as-the-agent
  (let [seen (atom nil)]
    (try
      (principal/configure! {:authorize (fn [p r] (reset! seen [p (dissoc r :args)]) false)})
      (let [r (tools/execute "read_file" {:path "/x"} {:actor :var :room {:id :r}})]
        (is (= :error (:type r)))
        (is (re-find #"Not authorized" (:error r)))
        (is (= :denied (get-in r [:authorization :decision]))))
      (is (= [(principal/agent-principal :var)
              {:op :tool/read_file :action :read :resource [:room "r"] :resolved {:room "r"}}]
             @seen))
      (testing "a call for nobody (no principal, no actor) is refused, unasked"
        (reset! seen nil)
        (let [r (tools/execute "read_file" {:path "/x"} {})]
          (is (= :error (:type r)))
          (is (re-find #"runs for nobody" (:error r))))
        (is (nil? @seen)))
      (finally (principal/configure! {})))))

;; ---------------------------------------------------------------------------
;; Declarations that point past their room

(defn- recorder [allow?]
  (let [seen (atom [])]
    [seen (fn [_ r] (swap! seen conj [(:action r) (:resource r) (:resolved r)]) (allow? r))]))

(deftest a-merge-needs-a-merge-on-the-parent-and-a-read-of-the-fork
  (let [fork (d/make-room {:id :authz-fork :parent-id :authz-open :ctx (:execution-ctx (daemon nil))
                           :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:execution-ctx (daemon nil))] (rreg/register! fork))
      (let [[seen authz] (recorder (constantly true))]
        (ops/authorize! (daemon authz) alice :room/merge {:room "authz-fork"})
        (is (= [[:read [:room "authz-fork"] {:room "authz-fork"}]
                [:merge [:room "authz-open"] {:room "authz-open"}]]
               @seen)))
      (testing "writing the fork is not enough: the merge lands on the parent"
        (let [[_ authz] (recorder #(not= :merge (:action %)))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (ops/authorize! (daemon authz) alice :room/merge {:room "authz-fork"})))))
      (testing "no parent, no merge (fail closed)"
        (is (thrown? clojure.lang.ExceptionInfo
                     (ops/authorize! (daemon (constantly true)) alice :room/merge {:room "authz-open"}))))
      (finally
        (binding [ec/*execution-context* (:execution-ctx (daemon nil))]
          (try (rreg/unregister! :authz-fork) (catch Throwable _ nil)))
        (d/close-room! fork)))))

(deftest a-room-workflow-benchmark-reads-its-room-and-creates-one
  (let [[seen authz] (recorder (constantly true))]
    (ops/authorize! (daemon authz) alice :catalog/benchmark {:workflow "authz-closed/sums" :models ["m"]})
    (is (= #{[:write [:global] nil] [:read [:room "authz-closed"] {:room "authz-closed"}]}
           (set @seen))))
  (testing "a catalog workflow names no room"
    (let [[seen authz] (recorder (constantly true))]
      (ops/authorize! (daemon authz) alice :catalog/benchmark {:workflow "wiki/v1" :models ["m"]})
      (is (= #{[:write [:global] nil]} (set @seen))))))

(deftest what-else-an-op-puts-to-work-is-authorized-too
  (let [[seen authz] (recorder (constantly true))]
    (ops/authorize! (daemon authz) alice :room/invite {:room "authz-open" :agent "var"})
    (is (some #{[:write [:agent "var"] nil]} @seen) "inviting an agent puts it to work"))
  (testing "a developer Run is the room's whole capability"
    (is (= :admin (:action (ops/authz-request (daemon nil) :workflow/start {:room "r" :task "t" :profile "developer"}))))
    (is (= :write (:action (ops/authz-request (daemon nil) :workflow/start {:room "r" :task "t"})))))
  (testing "an export to a file writes the host's state root"
    (let [[seen authz] (recorder (constantly true))]
      (ops/authorize! (daemon authz) alice :attempt/export {:room "authz-open" :file "x.jsonl"})
      (is (some #{[:admin [:global] nil]} @seen))))
  (is (= [:global] ((:resource (ops/authz-decl :catalog/promote)) {:room "r"}))
      "promoting a verifier is trusted host-wide"))

(deftest an-agents-turn-runs-its-tool-calls-as-the-agent
  ;; the real path: an llm-agent in a room, its turn (chat.agent) asking the
  ;; model, which calls a tool; the tool call is authorized as that agent on
  ;; that room before it runs
  (let [seen (atom [])
        asked (promise)
        calls (atom 0)
        r (d/make-room {:id :authz-turn :store (memory/make)})
        tool-call {:id "t1" :name "read_file" :input {:path "/nothing-here"}}]
    (try
      (principal/configure! {:authorize (fn [p req]
                                          (swap! seen conj [p (dissoc req :args)])
                                          (deliver asked true)
                                          false)})
      (with-redefs [dvergr.chat.agent/messages->api-format (fn [messages _ _] messages)
                    dvergr.model.chat/chat (fn [& _]
                                             (if (= 1 (swap! calls inc))
                                               {:content "" :tool-calls [tool-call] :usage {} :stop-reason :tool-use}
                                               {:content "done" :tool-calls [] :usage {} :stop-reason :stop}))]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r ((requiring-resolve 'dvergr.discourse.llm/llm-agent)
                     {:id :worker :spec {:provider :test :model "stub"}
                      :tools {"read_file" (tools/get-tool "read_file")}})))
        (binding [ec/*execution-context* (:ctx r)]
          (sp/spawn! (sp/spin (sp/await (d/ask r :worker {:content "read it"})))))
        (do
          (is (true? (deref asked 30000 ::timeout)) "the turn reached the authorizer")))
      (let [[p req] (first @seen)]
        (is (= (principal/agent-principal :worker) p))
        (is (= {:op :tool/read_file :action :read :resource [:room "authz-turn"] :resolved {:room "authz-turn"}}
               req)))
      (finally
        (principal/configure! {})
        (d/close-room! r)))))

;; ---------------------------------------------------------------------------
;; Creating: never someone else's room, and a failed grant can be retried

(defmacro ^:private with-system-db
  "Run `body` against a fresh system DB under a temporary home."
  [& body]
  `(let [prev# (dvergr.substrate.paths/home)]
     (dvergr.substrate.paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-principal-test-" (random-uuid)))
     (dvergr.system.db/reset-conn!)
     (try ~@body
          (finally (dvergr.system.db/reset-conn!) (dvergr.substrate.paths/set-home! prev#)))))

(deftest a-create-never-hands-over-an-existing-room
  (with-system-db
    (let [granted (atom [])
          fail? (atom false)
          c (:execution-ctx (daemon nil))
          dmn (assoc-in (daemon (constantly true)) [:config :auth :on-created]
                        (fn [p r] (when @fail? (throw (ex-info "grant store down" {})))
                          (swap! granted conj [(:principal/id p) r])))
          made (atom 0)]
      (with-redefs [rooms/create-room! (fn [{:keys [slug title]}]
                                         (swap! made inc)
                                         (dvergr.system.db/create-room! {:slug slug :name title})
                                         (when-not (rreg/lookup (keyword slug))
                                           (rreg/register! (d/make-room {:id (keyword slug) :ctx c :store (memory/make)})))
                                         (keyword slug))]
        (try
          (testing "a failed grant fails the create; the same call again grants it"
            (reset! fail? true)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same call again"
                                  (ops/invoke dmn alice :room/create {:title "A" :slug "authz-a"})))
            (reset! fail? false)
            (ops/invoke dmn alice :room/create {:title "A" :slug "authz-a"})
            (is (= [["party/alice" [:room "authz-a"]]] @granted))
            (is (= 1 @made) "the retry granted, it did not create again"))
          (testing "another principal's create of that slug is refused, and grants nothing"
            (let [e (try (ops/invoke dmn bob :room/create {:title "mine now" :slug "authz-a"}) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= 409 (ops/error-status e)))
              (is (= 1 (count @granted)))))
          (testing "an existing room created by the process is nobody's to claim"
            (ops/invoke dmn principal/local-principal :room/create {:title "L" :slug "authz-l"})
            (is (= 1 (count @granted)) "the local principal needs no grant")
            (is (= 409 (ops/error-status (try (ops/invoke dmn alice :room/create {:title "L" :slug "authz-l"}) nil
                                              (catch clojure.lang.ExceptionInfo e e))))))
          (testing "the agents a create joins are put to work"
            (let [[seen authz] (recorder (constantly true))]
              (ops/authorize! (daemon authz) alice :room/create {:title "x" :agents ["var" "huginn"]})
              (is (= #{[:write [:global] nil] [:write [:agent "var"] nil] [:write [:agent "huginn"] nil]}
                     (set @seen)))))
          (finally
            (binding [ec/*execution-context* c]
              (doseq [k [:authz-a :authz-l]]
                (when-let [r (rreg/lookup k)] (rreg/unregister! k) (d/close-room! r))))))))))

(deftest a-principal-opens-its-own-dm-with-an-agent
  (with-system-db
    (let [opened (atom [])
          c (:execution-ctx (daemon nil))]
      (with-redefs [dvergr.orchestration.daemon/ensure-agent-room!
                    (fn [_ agent-id & [{:keys [slug]}]]
                      (let [slug (or slug (str "dm-" (name agent-id)))]
                        (swap! opened conj slug)
                        (dvergr.system.db/create-room! {:slug slug :name slug})
                        (or (rreg/lookup (keyword slug))
                            (doto (d/make-room {:id (keyword slug) :ctx c :store (memory/make)}) rreg/register!))))]
        (try
          (let [dmn (daemon (constantly true))]
            (ops/invoke dmn alice :agent/open {:id "var"})
            (ops/invoke dmn bob :agent/open {:id "var"})
            (ops/invoke dmn principal/local-principal :agent/open {:id "var"})
            (let [[a b l] @opened]
              (is (= "dm-var" l) "the local user keeps the shared DM")
              (is (not= a b) "each principal its own")
              (is (every? #(re-matches #"dm-var-[0-9a-f]{8}" %) [a b]))))
          (finally
            (binding [ec/*execution-context* c]
              (doseq [s @opened]
                (when-let [r (rreg/lookup (keyword s))] (rreg/unregister! (keyword s)) (d/close-room! r))))))))))

(deftest a-forged-local-is-refused-by-decide-itself
  (is (false? (:allow? (principal/decide {:config {:auth {:authorize (constantly true)}}}
                                         {:principal/id :local :principal/kind :local}
                                         {:op :room/list :action :read :resource [:global]})))
      "even an authorizer that allows everything is not asked"))

(deftest commands-run-for-their-principal
  (let [cmd (requiring-resolve 'dvergr.discourse.commands/execute!)
        action (requiring-resolve 'dvergr.discourse.commands/command-action)]
    (is (= :read (action {:name "help" :kind :builtin})))
    (is (= :admin (action {:name "model" :kind :builtin})))
    (is (= :write (action {:name "x" :kind :prompt})))
    (is (= :admin (action {:name "x" :kind :handler})))
    (is (re-find #"runs for nobody" (:reply (cmd "/model x" {:available-tools []}))))
    (try
      (principal/configure! {:authorize (fn [_ r] (= :read (:action r)))})
      (is (re-find #"Not authorized" (:reply (cmd "/model x" {:principal bob :available-tools []}))))
      (is (not (re-find #"Not authorized" (str (:reply (cmd "/help" {:principal bob :available-tools []
                                                                     :notify! identity}))))))
      (finally (principal/configure! {})))))
