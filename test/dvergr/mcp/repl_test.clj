(ns dvergr.mcp.repl-test
  "The ops a connection may call, in its room REPL: the same authority as its
   tools, validated, and never closing the room the REPL runs in."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.mcp.repl :as repl]
            [dvergr.mcp.surface :as surface]
            [sci.core :as sci]))

(def ^:private calls (atom []))

(defn- ns-map [profile]
  (with-redefs [dvergr.ops/invoke (fn [_ op args] (swap! calls conj [op args]) {:op op})
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
    (with-redefs [dvergr.ops/invoke (fn [_ op args] (swap! calls conj [op args]) {:op op})
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
    (with-redefs [dvergr.ops/invoke (fn [_ op _] {:op op})]
      (repl/install! ctx ::daemon (surface/selection {:profile "offload"}) {:id :here})
      (is (= {:op :room/list} (sci/eval-string* ctx "(dvergr.ops/room-list {})")))
      (is (re-find #"(?i)room" (str (sci/eval-string* ctx "(:doc (meta dvergr.ops/room-list))")))))))

(deftest each-selection-has-its-own-session
  (is (= :mcp/offload (repl/session-actor (surface/selection {:profile "offload"}))))
  (is (not= (repl/session-actor (surface/selection {:profile "offload"}))
            (repl/session-actor (surface/selection {:profile "readonly"}))))
  (is (not= :mcp/offload (repl/session-actor (surface/selection {:profile "offload" :toolsets ["bench"]})))))
