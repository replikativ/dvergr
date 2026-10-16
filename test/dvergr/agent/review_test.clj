(ns dvergr.agent.review-test
  "The review stage over retained worlds, with a scripted reviewer: checks
   first (a failed one never asks the reviewer), the verdict, spot checks,
   merges pinned to the reviewed state, escalation and the stored record."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.review :as review]
            [dvergr.catalog :as catalog]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.wiki-v4 :as v4]
            [dvergr.catalog.workspace]
            [dvergr.discourse :as d]
            [muschel.fs]
            [dvergr.ops :as ops]
            [dvergr.rooms.forks]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.principal :as principal]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-review-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [dmn (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* dmn] (f))
          (finally
            (try (daemon/stop! dmn) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- op [k args] (ops/invoke *daemon* principal/local-principal k args))

(defn- texts [m] (into {} (map (fn [[p t]] [p {:text t}])) m))

(defn- room-with-fork
  "A room with `base` files committed, and a fork of it with `changes`
   committed: `[parent fork-id]`."
  [slug base changes]
  (let [room (:id (op :room/create {:title slug :slug slug}))]
    (when (seq base) (op :room/write {:room room :files (texts base)}))
    (let [fork (:id (op :room/fork {:room room}))]
      (when (seq changes) (op :room/write {:room fork :files (texts changes)}))
      [(ops/resolve-room *daemon* room) fork])))

(defn- fork-id [fork] (:id (ops/resolve-room *daemon* fork)))

(defn- scripted
  "A reviewer that returns `verdict` and counts its calls."
  [calls verdict]
  (fn [input] (swap! calls conj input) verdict))

(def ^:private wiki-only
  {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]]]
   :review/reviewer {:instructions "Approve faithful wiki updates."}})

(defn- escalations
  "The room's durable escalation messages."
  [room]
  (filter #(= :review/escalated (get-in % [:metadata :kind]))
          (d/messages (ops/resolve-room *daemon* (name (:id room))))))

(defn- raw-write!
  "Write a file into a room's workspace without committing it, as a tool does."
  [room path text]
  (muschel.fs/write-string! (dvergr.catalog.workspace/room-fs (ops/resolve-room *daemon* room)) path text false))

(deftest an-approved-world-is-merged-and-recorded
  (let [[parent fork] (room-with-fork "rv-approve" {"docs/a.md" "A."} {"wiki/a.md" "# A\n"})
        calls (atom [])
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted calls {:review/verdict :approve :review/reasons ["faithful"]})
                           :invoke op)]
    (is (= :merged (:review/decision rv)))
    (is (= 1 (count @calls)))
    (is (= ["wiki/a.md"] (get-in (first @calls) [:review/diff :files])) "the reviewer sees what changed")
    (is (every? :pass? (:review/checks rv)))
    (is (= "# A\n" (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/wiki") "/wiki/a.md")) "the parent has the merged page")
    (is (= [rv] (review/reviews parent)) "the Review is stored in the room")
    (let [[listed] (op :review/list {:room "rv-approve"})]
      (is (= "merged" (:decision listed)))
      (is (= (:review/state-token rv) (:state_token listed)))
      (is (= "approve" (get-in listed [:verdict :review/verdict]))))))

(deftest a-failed-check-escalates-without-asking-the-reviewer
  (let [[parent fork] (room-with-fork "rv-scope" {} {"wiki/a.md" "# A\n" "schedules.edn" "{:every 1}"})
        calls (atom [])
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted calls {:review/verdict :approve}))]
    (is (= :escalated (:review/decision rv)))
    (is (empty? @calls) "the reviewer is never asked")
    (is (= ["schedules.edn"] (get-in (second (:review/checks rv)) [:evidence :outside])))
    (is (some? (ops/resolve-room *daemon* fork)) "the world is kept for a person")
    (let [[m] (escalations parent)]
      (is (= (:review/id rv) (get-in m [:metadata :object :id])) "the message names the Review")
      (is (str/includes? (str (:content m)) "needs a person")))
    (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")) "nothing merged")
    (is (= 1 (count (op :review/list {:room "rv-scope" :decision "escalated"}))))))

(deftest the-reviewer-can-escalate
  (let [[parent fork] (room-with-fork "rv-esc" {} {"wiki/a.md" "# A\nThe river flows west.\n"})
        rv (review/review! parent (fork-id fork) wiki-only
                           :reviewer (scripted (atom []) {:review/verdict :escalate
                                                          :review/reasons ["contradicts the charter"]}))]
    (is (= :escalated (:review/decision rv)))
    (is (some #{"contradicts the charter"} (:review/reasons rv)))
    (is (= :escalate (get-in rv [:review/verdict :review/verdict])))))

(deftest spot-checks-sample-approved-reviews-deterministically
  (let [id (random-uuid)]
    (is (= (review/spot-checked? id {:rate 0.5 :seed 1}) (review/spot-checked? id {:rate 0.5 :seed 1})))
    (is (not (review/spot-checked? id {:rate 0.0 :seed 1})))
    (is (review/spot-checked? id {:rate 1.0 :seed 1}))
    (let [n (count (filter (fn [_] (review/spot-checked? (random-uuid) {:rate 0.1 :seed 3})) (range 2000)))]
      (is (< 120 n 280) (str n " of 2000 at rate 0.1"))))
  (let [[parent fork] (room-with-fork "rv-spot" {} {"wiki/a.md" "# A\n"})
        rv (review/review! parent (fork-id fork) (assoc wiki-only :review/spot-check {:rate 1.0 :seed 1})
                           :reviewer (scripted (atom []) {:review/verdict :approve}))]
    (is (= :escalated (:review/decision rv)))
    (is (:review/spot-check? rv))))

(deftest a-world-changed-after-review-is-not-merged
  (let [[parent fork] (room-with-fork "rv-race" {} {"wiki/a.md" "# A\n"})
        ;; the world changes while the reviewer reads it
        sneaky (fn [_] (op :room/write {:room fork :files (texts {"wiki/b.md" "# B\n"})})
                 {:review/verdict :approve})
        rv (review/review! parent (fork-id fork) wiki-only :reviewer sneaky :invoke op)]
    (is (= :escalated (:review/decision rv)))
    (is (some #(str/includes? % "changed since") (:review/reasons rv)) (pr-str (:review/reasons rv)))
    (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")))))

(deftest on-fail-discard-and-propose
  (testing "a failed check discards the world under :review/on-fail :discard"
    (let [[parent fork] (room-with-fork "rv-discard" {} {"notes.md" "x"})
          rv (review/review! parent (fork-id fork) (assoc wiki-only :review/on-fail :discard)
                             :reviewer (scripted (atom []) {:review/verdict :approve}))]
      (is (= :discarded (:review/decision rv)))
      (is (empty? (escalations parent)))))
  (testing ":review/on-pass :propose leaves the merge to a person"
    (let [[parent fork] (room-with-fork "rv-propose" {} {"wiki/a.md" "# A\n"})
          rv (review/review! parent (fork-id fork) (assoc wiki-only :review/on-pass :propose)
                             :reviewer (scripted (atom []) {:review/verdict :approve}))]
      (is (= :escalated (:review/decision rv)))
      (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md")))))
  (testing "a spec that names a reviewer needs one"
    (let [[parent fork] (room-with-fork "rv-none" {} {"wiki/a.md" "# A\n"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a reviewer"
                            (review/review! parent (fork-id fork) wiki-only))))))

(deftest the-wiki-v4-check-scores-the-world
  (let [w (gen/world 1)
        docs (into {} (map (fn [[p t]] [(subs p 1) t])) (gen/documents w))
        wiki (into {} (map (fn [[p t]] [(subs p 1) t])) (v4/reference-wiki w))
        spec {:review/checks [:fork/review-tier [:diff/only-paths ["wiki/"]] :wiki/v4]}]
    (testing "the reference wiki passes and is merged with no reviewer"
      (let [[parent fork] (room-with-fork "rv-v4-good" docs wiki)
            rv (review/review! parent (fork-id fork) spec)]
        (is (= :merged (:review/decision rv)) (pr-str (:review/checks rv)))
        (is (= 1.0 (get-in rv [:review/checks 2 :evidence :reward])))))
    (testing "an unquoted wiki fails the check"
      (let [unquoted (into {} (map (fn [[p t]] [p (str/replace t #"(?m)^> .*\n" "")])) wiki)
            [parent fork] (room-with-fork "rv-v4-bad" docs unquoted)
            rv (review/review! parent (fork-id fork) spec)]
        (is (= :escalated (:review/decision rv)))
        (is (some #{:every-claim-quoted?} (get-in rv [:review/checks 2 :evidence :failed])))))))

(deftest a-reviewer-must-approve-explicitly
  (doseq [[slug reviewer why] [["rv-nil" (fn [_] nil) "no verdict"]
                               ["rv-odd" (fn [_] {:review/verdict :sure}) "no verdict"]
                               ["rv-throw" (fn [_] (throw (ex-info "provider down" {}))) "failed: provider down"]]]
    (let [[parent fork] (room-with-fork slug {} {"wiki/a.md" "# A\n"})
          rv (review/review! parent (fork-id fork) wiki-only :reviewer reviewer)]
      (is (= :escalated (:review/decision rv)) slug)
      (is (some #(str/includes? % why) (:review/reasons rv)) (pr-str (:review/reasons rv)))
      (is (= [rv] (review/reviews parent)) "recorded")
      (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/wiki/a.md"))))))

(deftest uncommitted-files-are-reviewed-and-pinned
  (testing "an uncommitted file under the declared paths is reviewed and merged"
    (let [[parent fork] (room-with-fork "rv-dirty" {} {})]
      (raw-write! fork "/wiki/a.md" "# A\n")
      (let [calls (atom [])
            rv (review/review! parent (fork-id fork) wiki-only :reviewer (scripted calls {:review/verdict :approve}) :invoke op)]
        (is (= :merged (:review/decision rv)) (pr-str rv))
        (is (= ["wiki/a.md"] (get-in (first @calls) [:review/diff :files])))
        (is (= "# A\n" (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/wiki") "/wiki/a.md"))))))
  (testing "an uncommitted write while the reviewer reads is not merged"
    (doseq [[slug invoke] [["rv-dirty-race" op] ["rv-dirty-race-direct" nil]]]
      (let [[parent fork] (room-with-fork slug {} {"wiki/a.md" "# A\n"})
            sneaky (fn [_] (raw-write! fork "/schedules.edn" "{:every 1}") {:review/verdict :approve})
            rv (review/review! parent (fork-id fork) wiki-only :reviewer sneaky :invoke invoke)]
        (is (= :escalated (:review/decision rv)) slug)
        (is (some #(str/includes? % "changed since") (:review/reasons rv)) (pr-str (:review/reasons rv)))
        (is (nil? (get (catalog/read-tree (ops/resolve-room *daemon* (name (:id parent))) "/") "/schedules.edn")))))))

(deftest a-database-change-is-outside-the-declared-paths
  (let [changed? #'review/system-changed?]
    (is (changed? {:summary {:added-datoms 0 :removed-datoms 3}}) "retractions count")
    (is (changed? {:summary {:added-datoms 2}}))
    (is (not (changed? {:summary {:added-datoms 0 :removed-datoms 0}})))
    (is (changed? {:error "diff failed"}))
    (is (changed? {:intents [{:op :book}]}))
    (is (changed? {:something :unknown}) "unclear goes to a person")))

(deftest the-state-token-covers-uncommitted-names-contents-and-modes
  (let [[_ fork] (room-with-fork "rv-token" {} {})
        f #(ops/resolve-room *daemon* fork)
        token #(dvergr.rooms.forks/state-token (f))]
    (raw-write! fork "/wiki/a.md " "one")
    (let [t1 (token)]
      (is (= ["wiki/a.md "] (map :path (dvergr.rooms.forks/uncommitted (f)))) "the exact name, its space kept")
      (raw-write! fork "/wiki/a.md " "two")
      (let [t2 (token)]
        (is (not= t1 t2) "a changed content changes the token")
        (muschel.fs/chmod (dvergr.catalog.workspace/room-fs (f)) "/wiki/a.md " 0755)
        (is (not= t2 (token)) "a changed mode too")))))
