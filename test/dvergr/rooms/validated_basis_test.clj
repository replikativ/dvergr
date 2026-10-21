(ns dvergr.rooms.validated-basis-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.discourse :as discourse]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.rooms.publication :as publication]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.content :as content]
            [geschichte.repo :as repo]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]))

(defn- with-repository [history? f]
  (let [cfg (assoc gs/repository-options :keep-history? history?
                   :store {:backend :memory :id (random-uuid)})
        room (discourse/make-room {:id (keyword (str "validated-basis-" (random-uuid)))})]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (repo/init! conn {:name "Validated source"})
        (repo/write! conn "page.md" (.getBytes "original" "UTF-8"))
        (repo/write! conn "other.md" (.getBytes "reused" "UTF-8"))
        (repo/stage-all! conn)
        (repo/commit! conn {:message "Seed"})
        (binding [ec/*execution-context* (:ctx room)]
          (let [system (gy/create conn {:system-name "validated-source"})]
            (ygg/register! system)
            (f room system conn)))
        (finally
          (let [shutdown (discourse/close-registered-rooms! (:ctx room))]
            (try (d/release conn) (d/delete-database cfg)
                 (finally (discourse/close-shutdown-contexts! shutdown)))))))))

(defn- snapshot [conn]
  {:head (some-> (repo/head-commit conn) :geschichte.commit/id)
   :worktree (repo/worktree conn) :index (repo/index-tree conn)})

(defn- options [conn]
  {:expected-worktree {"/page.md" (get (repo/worktree conn) "page.md")}
   :validated-basis (file-tx/capture-basis @conn)})

(defn- failure [f] (try (f) nil (catch Throwable e e)))

(defn- boundary [f]
  (constantly {:handlers [(fn [effect next] (f effect next))]}))

(deftest unchanged-basis-works-with-clocks-and-new-reused-or-identical-content
  (doseq [history? [false true] text ["new content" "reused" "original"]]
    (with-repository history?
      (fn [room system conn]
        (let [result (files/write-to! room system {"page.md" {:text text}} (options conn))]
          (is (:applied? result))
          (is (= text (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8")))
          (is (empty? (:staged (repo/status conn)))))))))

(deftest stale-schema-and-noop-transactions-refuse-even-identical-content
  (doseq [tx [[] [{:db/ident :test/new-field :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}]]]
    (with-repository false
      (fn [room system conn]
        (let [opts (options conn) before (snapshot conn)]
          (d/transact conn tx)
          (let [error (failure #(files/write-to! room system {"page.md" {:text "original"}} opts))]
            (is (file-tx/native-basis-refusal? error))
            (is (not (publication/uncertain? error)))
            (is (= before (snapshot conn)))))))))

(deftest same-commit-with-advanced-max-tx-is-not-the-same-validation-basis
  (with-repository false
    (fn [_ _ conn]
      (let [db @conn basis (file-tx/capture-basis db)
            evolved (d/db-with db [])]
        (is (= (d/commit-id db) (d/commit-id evolved)))
        (is (< (:max-tx db) (:max-tx evolved)))
        (is (file-tx/native-basis-refusal? (failure #(file-tx/assert-basis! evolved basis))))))))

(deftest closed-basis-and-physical-logical-target-binding
  (with-repository false
    (fn [_ _ conn]
      (let [basis (file-tx/capture-basis @conn)]
        (is (= [] (file-tx/assert-basis! @conn basis)))
        (doseq [[key value] [[:backend :other] [:store (random-uuid)] [:branch :other]
                             [:repository (random-uuid)] [:ref "refs/heads/other"]
                             [:commit (random-uuid)] [:max-tx (inc (:max-tx basis))]]]
          (is (file-tx/native-basis-refusal?
               (failure #(file-tx/assert-basis! @conn (assoc basis key value))))))
        (doseq [invalid [(assoc basis :extra true) (dissoc basis :max-tx)
                         (assoc basis :store "not-a-uuid") (assoc basis :max-tx -1)
                         (assoc basis :ref "refs/heads/\nunsafe")]]
          (is (= ::file-tx/invalid-basis (:type (ex-data (failure #(file-tx/validate-basis! invalid)))))))))))

(deftest unsupported-batches-refuse-before-any-effect
  (with-repository false
    (fn [room system conn]
      (let [before (snapshot conn) called (atom 0)
            opts (assoc (options conn) :boundary (boundary (fn [effect next]
                                                             (swap! called inc) (next effect))))]
        (doseq [[batch extra] [[{"new.md" {:text "new"}} {:expected-worktree {"/new.md" nil}}]
                               [{"page.md" nil} {}]
                               [{"page.md" {:text "new"} "other.md" {:text "new"}} {}]
                               [{"page.md" {:text "new"}} {:expected-worktree {"/page.md" nil}}]
                               [{"page.md" {:text "new"}} {:publication nil}]
                               [{"page.md" {:text "new"}} {:expected-subtree nil}]]]
          (is (some? (failure #(files/write-to! room system batch (merge opts extra)))))
          (is (= before (snapshot conn))))
        (is (zero? @called)))
      (is (contains? files/write-capabilities :validated-basis)))))

(deftest native-basis-refusal-preserves-intervening-index-staging
  (with-repository false
    (fn [room system conn]
      (let [opts (options conn) staged (atom nil)
            error (failure
                   #(files/write-to!
                     room system {"page.md" {:text "must not write"}}
                     (assoc opts :boundary
                            (boundary (fn [effect next]
                                        (when (= :fs/write (:effect effect))
                                          (repo/write! conn "page.md" (.getBytes "human staged change" "UTF-8"))
                                          (repo/stage! conn ["page.md"])
                                          (reset! staged (repo/index-tree conn)))
                                        (next effect))))))]
        (is (file-tx/native-basis-refusal? error))
        (is (some? @staged))
        (is (= @staged (repo/index-tree conn)))
        (is (= "human staged change" (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8")))))))

(deftest handler-errors-cannot-manufacture-native-no-write-evidence
  (with-repository false
    (fn [room system conn]
      (let [stale (assoc (file-tx/capture-basis @conn) :max-tx 0)
            authentic (failure #(file-tx/assert-basis! @conn stale))]
        (doseq [error [(ex-info "forged refusal" {:type ::file-tx/stale-basis}) authentic]]
          (let [before (snapshot conn)
                result (failure
                        #(files/write-to!
                          room system {"page.md" {:text "temporary"}}
                          (assoc (options conn) :boundary
                                 (boundary (fn [effect next]
                                             (let [result (next effect)]
                                               (when (= :fs/write (:effect effect)) (throw error))
                                               result))))))]
            (is (publication/uncertain? result)
                "a handler's replayed refusal cannot certify no native write")
            (is (= (:index before) (repo/index-tree conn)))
            (is (= "temporary" (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8")))))))))

(deftest handler-failures-preserve-human-staging-before-and-after-native-forwarding
  (doseq [forward? [false true]]
    (with-repository false
      (fn [room system conn]
        (let [staged (atom nil)
              error (failure
                     #(files/write-to!
                       room system {"page.md" {:text "agent change"}}
                       (assoc (options conn) :boundary
                              (boundary (fn [effect next]
                                          (if (= :fs/write (:effect effect))
                                            (do
                                              (when forward? (next effect))
                                              (repo/write! conn "page.md" (.getBytes "later human change" "UTF-8"))
                                              (repo/stage! conn ["page.md"])
                                              (reset! staged (repo/index-tree conn))
                                              (throw (ex-info "handler failed" {:type ::file-tx/stale-basis})))
                                            (next effect)))))))]
          (is (some? error))
          (is (= forward? (publication/uncertain? error)))
          (is (not (file-tx/native-basis-refusal? error)))
          (is (= @staged (repo/index-tree conn)))
          (is (= "later human change"
                 (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8"))))))))

(deftest skipped-and-repeated-forwarding-retain-physical-receipt-semantics
  (with-repository false
    (fn [room system conn]
      (let [before (snapshot conn)
            skipped (files/write-to! room system {"page.md" {:text "skipped"}}
                                     (assoc (options conn) :boundary (boundary (fn [_ _] :skip))))]
        (is (false? (:applied? skipped)))
        (is (= before (snapshot conn))))
      (let [calls (atom 0) native content/transact-content!
            result (with-redefs [content/transact-content! (fn [& args]
                                                             (when (= 4 (count args)) (swap! calls inc))
                                                             (apply native args))]
                     (files/write-to! room system {"page.md" {:text "once"}}
                                      (assoc (options conn) :boundary
                                             (boundary (fn [effect next]
                                                         (let [result (next effect)]
                                                           (when (#{:git/commit :fs/write} (:effect effect)) (next effect))
                                                           result))))))]
        (is (:applied? result))
        (is (= 1 @calls))))))

(deftest committed-content-with-lost-ack-is-uncertain-never-compensated
  (with-repository false
    (fn [room system conn]
      (let [native content/transact-content! opts (options conn)
            error (with-redefs [content/transact-content!
                                (fn [& args]
                                  (apply native args)
                                  (throw (ex-info "lost acknowledgement" {})))]
                    (failure #(files/write-to! room system {"page.md" {:text "committed bytes"}} opts)))]
        (is (publication/uncertain? error))
        (is (not (file-tx/native-basis-refusal? error)))
        (is (= "committed bytes"
               (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8")))))))

(deftest outer-post-commit-handler-failure-retains-uncertainty-and-native-receipt
  (with-repository false
    (fn [room system conn]
      (let [error (failure
                   #(files/write-to!
                     room system {"page.md" {:text "fully committed"}}
                     (assoc (options conn) :boundary
                            (boundary (fn [effect next]
                                        (let [result (next effect)]
                                          (when (= :git/commit (:effect effect))
                                            (throw (ex-info "outer post-forward failure" {})))
                                          result))))))]
        (is (publication/uncertain? error))
        (is (some? (get-in (ex-data error) [:applied :commit])))
        (is (= "fully committed"
               (String. ^bytes (repo/read-entry conn (get (repo/worktree conn) "page.md")) "UTF-8")))))))

(deftest skipped-inner-forwarding-cannot-certify-identical-save-or-write-later
  (with-repository false
    (fn [room system conn]
      (let [before (snapshot conn) saved (atom nil)
            error (failure
                   #(files/write-to!
                     room system {"page.md" {:text "original"}}
                     (assoc (options conn) :boundary
                            (boundary (fn [effect next]
                                        (if (= :fs/write (:effect effect))
                                          (do (reset! saved (fn [] (next effect))) :skip)
                                          (next effect)))))))]
        (is (= ::files/not-applied (:type (ex-data error))))
        (is (not (publication/uncertain? error)))
        (is (= before (snapshot conn)))
        (is (some? @saved))
        (is (some? (failure @saved)))
        (is (= before (snapshot conn)))))))

(deftest skipped-outer-forwarding-is-sealed-before-return
  (with-repository false
    (fn [room system conn]
      (let [before (snapshot conn) saved (atom nil)
            result (files/write-to!
                    room system {"page.md" {:text "must never write"}}
                    (assoc (options conn) :boundary
                           (boundary (fn [effect next]
                                       (reset! saved (fn [] (next effect))) :skip))))]
        (is (false? (:applied? result)))
        (is (some? @saved))
        (is (some? (failure @saved)))
        (is (= before (snapshot conn)))))))
