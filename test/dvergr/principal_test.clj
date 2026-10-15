(ns dvergr.principal-test
  "One principal, one authorization check: every op and every registry tool
   declares what it acts on, `ops/invoke` asks the authorizer before it acts,
   a listing shows only what the caller may read, a post never names someone
   else, and a host can add ops and seams without a daemon."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.context :as ctx]
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

(deftest acting-on-a-room-is-never-global
  (testing "an op whose schema names a required room acts on that room"
    (doseq [[op _] (ops/operations)
            :let [entry (some #(when (and (vector? %) (= :room (first %))) %)
                              (rest (ops/malli-schema op)))]
            :when (and entry (not (and (map? (second entry)) (:optional (second entry)))))
            ;; these two create the room their `room` names: there is none to act on yet
            :when (not (#{:room/import} op))]
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
    (is (principal/local? (auth {:transport :mcp-http :bearer "t" :local-token "t"})))
    (is (nil? (auth {:transport :mcp-http :bearer "x" :local-token "t"})))
    (is (nil? (auth {:transport :mcp-http :bearer nil :local-token "t"})))))

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
      (testing "a host call (no principal, no actor) is not asked, as before"
        (reset! seen nil)
        (tools/execute "nobody_knows" {} {})
        (is (nil? @seen)))
      (finally (principal/configure! {})))))
