(ns dvergr.substrate.kontor-book-test
  "A room's business book settles by replay: a fork books as the root does,
   the parent renumbers what it takes in, review sees intents and the
   parent's claims, and a claim both sides made blocks the merge."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.substrate.kontor-book :as book]
            [dvergr.system.rooms :as rooms]
            [kontor.book :as kbook]
            [kontor.numbering :as numbering]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(def ^:private eur [:kontor.commodity/symbol "EUR"])
(def ^:private ar [:kontor.account/path "Assets:Receivable"])
(def ^:private rev [:kontor.account/path "Income:Sales"])

(defn- book-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true :commit-graph? true :schema-flexibility :write}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- seed! [conn]
  (d/transact conn [{:kontor.commodity/symbol "EUR" :kontor.commodity/name "Euro" :kontor.commodity/precision 2}
                    {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                    {:kontor.account/path "Assets:Receivable" :kontor.account/type :asset}
                    {:kontor.account/path "Income:Sales" :kontor.account/type :income}])
  (numbering/configure-journal! conn [:kontor.journal/code "SALE"]
                                {:prefix "RE/{year}/" :reset :yearly :padding 4}))

(defn- conn-in [fh] (ygg/with-fork fh (:conn (ygg/system "book"))))

(defn- sell! [conn amount date & [opts]]
  (kbook/sell! conn (merge {:debit-account ar :credit-account rev :amount amount
                            :commodity eur :effective-date date}
                           opts)))

(defn- numbers [conn]
  (sort (d/q '[:find [?x ...] :where [?t :kontor.transaction/sequence-number _]
               [?t :kontor.transaction/external-id ?x]]
             (d/db conn))))

(deftest a-fork-s-book-settles-by-replay
  (let [ctx (sp/create-execution-context)]
    (sp/with-context ctx
      (let [conn (book-conn)]
        (book/register-book! conn "book")
        (seed! conn)
        (sell! conn 100 #inst "2026-03-01")
        (let [w (ygg/fork!)]
          (sell! (conn-in w) 250 #inst "2026-03-02")
          (is (= ["RE/2026/0001" "RE/2026/0002"] (numbers (conn-in w))))
          ;; the room books meanwhile
          (sell! conn 40 #inst "2026-03-02")
          (testing "review reads intents, not datoms"
            (is (= 1 (count (get-in (ygg/context-diff (:child-ctx w)) ["book" :intents]))))
            (is (empty? (ygg/context-conflicts (:child-ctx w)))))
          (let [result (ygg/merge-fork! w)]
            (is (= {"RE/2026/0002" "RE/2026/0003"} (get-in result [:stamps "book"]))))
          (is (= ["RE/2026/0001" "RE/2026/0002" "RE/2026/0003"] (numbers conn))
              "gapless: the fork's entry took the room's next number"))))))

(deftest a-bank-line-both-sides-matched-blocks-the-merge
  (let [ctx (sp/create-execution-context)]
    (sp/with-context ctx
      (let [conn (book-conn)]
        (book/register-book! conn "book")
        (seed! conn)
        (let [w (ygg/fork!)]
          (sell! (conn-in w) 70 #inst "2026-03-02" {:external-id "PAY-line-7"})
          (sell! conn 70 #inst "2026-03-02" {:external-id "PAY-line-7"})
          (is (= [{:system "book" :key [:external-id "PAY-line-7"]}]
                 (mapv #(select-keys % [:system :key]) (ygg/context-conflicts (:child-ctx w)))))
          (is (= ::ygg/merge-conflict
                 (try (ygg/merge-fork! w) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (ygg/discard-fork! w))))))

(deftest a-room-book-registers-with-its-settlement-hooks
  ;; the path a room's :book system takes when the room is hydrated
  (let [dir (str (System/getProperty "java.io.tmpdir") "/room-book-" (random-uuid))
        ctx (sp/create-execution-context)]
    (sp/with-context ctx
      (#'rooms/register-system-into-current! {:system/type :book :system/scope dir})
      (let [sid (#'rooms/book-system-name dir)
            conn (:conn (ygg/system sid))]
        (seed! conn)
        (let [w (ygg/fork!)]
          (sell! (ygg/with-fork w (:conn (ygg/system sid))) 30 #inst "2026-03-02")
          (is (= 1 (count (get-in (ygg/context-diff (:child-ctx w)) [sid :intents]))))
          (ygg/merge-fork! w)
          (is (= ["RE/2026/0001"] (numbers conn))))))))

