(ns dvergr.mcp.repl-test
  "The ops a connection may call, in its room REPL: the same authority as its
   tools, validated, and never closing the room the REPL runs in."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.mcp.repl :as repl]
            [dvergr.mcp.surface :as surface]
            [dvergr.principal :as principal]
            [sci.core :as sci]))

;; the evaluation calling the functions: the local principal, every op
(use-fixtures :each
  (fn [f] (binding [repl/*caller* {:principal principal/local-principal
                                   :selection (surface/selection {:profile "admin"})}]
            (f))))

(def ^:private calls (atom []))

(defn- ns-map [profile]
  (with-redefs [dvergr.ops/invoke (fn [_ _ op args] (swap! calls conj [op args]) {:op op})
                dvergr.ops/resolve-room (fn [_ r] {:id (keyword r)})]
    (repl/ns-map ::daemon (surface/selection {:profile profile}) {:id :here})))

(deftest a-connection-gets-the-ops-its-selection-shows
  (let [offload (set (keys (ns-map "offload")))
        readonly (set (keys (ns-map "readonly")))
        admin (set (keys (ns-map "admin")))]
    (is (every? offload '[room-list catalog-list job-status scorecard-detail call]))
    (is (not (contains? offload 'room-purge)) "admin ops are not on the default profile")
    (is (contains? admin 'room-purge))
    (is (contains? readonly 'scorecard-detail))
    (is (not-any? readonly '[room-create room-delete catalog-benchmark]) "read-only means read-only")))

(deftest a-call-is-validated-and-runs-the-op
  (reset! calls [])
  (let [m (ns-map "admin")]
    (with-redefs [dvergr.ops/invoke (fn [_ _ op args] (swap! calls conj [op args]) {:op op})
                  dvergr.ops/resolve-room (fn [_ r] {:id (keyword r)})]
      (is (= {:op :room/list} ((get m 'room-list) {})))
      (is (= {:op :room/list} ((get m 'call) :room/list {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"room-messages"
                            ((get m 'room-messages) {:limit "ten"})) "schema checked: the room is missing")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot close the room this REPL runs in"
                            ((get m 'room-delete) {:room "here"})))
      (is (= {:op :room/delete} ((get m 'room-delete) {:room "elsewhere"}))))
    (is (= [[:room/list {}] [:room/list {}] [:room/delete {:room "elsewhere"}]] @calls))))

(deftest unavailable-ops-are-refused-by-call
  (let [m (ns-map "readonly")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available"
                          ((get m 'call) :room/create {:title "x"})))))

(deftest the-namespace-documents-itself-in-sci
  (let [ctx (sci/init {})]
    (with-redefs [dvergr.ops/invoke (fn [_ _ op _] {:op op})]
      (repl/install! ctx ::daemon (surface/selection {:profile "offload"}) {:id :here})
      (is (= {:op :room/list} (sci/eval-string* ctx "(dvergr.ops/room-list {})")))
      (is (re-find #"(?i)room" (str (sci/eval-string* ctx "(:doc (meta dvergr.ops/room-list))")))))))

(deftest each-selection-has-its-own-session
  (is (= :mcp/offload (repl/session-actor (surface/selection {:profile "offload"}))))
  (is (not= (repl/session-actor (surface/selection {:profile "offload"}))
            (repl/session-actor (surface/selection {:profile "readonly"}))))
  (is (not= :mcp/offload (repl/session-actor (surface/selection {:profile "offload" :toolsets ["bench"]})))))

(deftest the-code-profile-lists-the-repl-and-calls-the-api-through-it
  (let [code (set (keys (ns-map "code")))]
    (is (every? code '[room-list catalog-benchmark job-status scorecard-detail room-fork]))
    (is (not-any? code '[room-purge room-unarchive agent-delete]) "admin ops stay admin")))

(deftest agent-ops-take-their-wire-values-through-the-repl
  ;; the REPL validates against the op's schema before the op normalizes:
  ;; the schema must let through what the op accepts
  (reset! calls [])
  (let [m (ns-map "admin")]
    (with-redefs [dvergr.ops/invoke (fn [_ _ op args] (swap! calls conj [op args]) {:op op})]
      ((get m 'agent-update) {:id "a" :patch {:budget-dollars 2 :tags "a b"}})
      ((get m 'agent-update) {:id "a" :patch {:budget-dollars 1.5}})
      ((get m 'agent-create) {:id "b" :tools ["read_file"] :budget-dollars 3})
      (is (= 3 (count @calls)) "integer and double budgets, tag text and tool names pass")
      (is (thrown? clojure.lang.ExceptionInfo
                   ((get m 'agent-update) {:id "a" :patch {:budget-dollars "lots"}}))))))

(deftest a-function-runs-for-the-caller-of-the-moment-not-its-definer
  ;; A installs the namespace (or defines a closure over it) in its session;
  ;; whoever calls it later acts with their own authority, and nobody acts
  ;; outside an evaluation.
  (let [a {:principal/id "party/a" :principal/kind :human :principal/scopes #{:dvergr/admin}}
        b {:principal/id "party/b" :principal/kind :human :principal/scopes #{:dvergr/admin}}
        sel (surface/selection {:profile "admin"})
        seen (atom [])
        m (binding [repl/*caller* {:principal a :selection sel}] (ns-map "admin"))
        room-list (get m 'room-list)]
    (with-redefs [dvergr.ops/invoke (fn [_ p op _] (swap! seen conj [(:principal/id p) op]) {:op op})]
      (binding [repl/*caller* {:principal a :selection sel}] (room-list {}))
      (binding [repl/*caller* {:principal b :selection sel}] (room-list {}))
      (is (= [["party/a" :room/list] ["party/b" :room/list]] @seen)
          "the same function, called by B, runs as B")
      (binding [repl/*caller* {:principal b :selection (surface/selection {:profile "readonly"})}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available"
                              ((get m 'room-create) {:title "x"}))
            "nor with A's selection: B's read-only one refuses a write"))
      (binding [repl/*caller* nil]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only inside the evaluation"
                              (room-list {}))
            "outside an evaluation it runs for nobody")))))

(deftest a-principal-has-sessions-of-its-own
  (let [sel (surface/selection {:profile "offload"})
        a {:principal/id "party/a" :principal/kind :human}
        b {:principal/id "party/b" :principal/kind :human}]
    (is (= :mcp/offload (repl/session-actor sel principal/local-principal)))
    (is (not= (repl/session-actor sel a) (repl/session-actor sel b)))
    (is (not= :mcp/offload (repl/session-actor sel a)))))
