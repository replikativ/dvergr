(ns dvergr.authority-test
  "`can?` over room relations (doc/effects.md, cross-room decision): its
   cases, and its laws as properties over random room trees."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dvergr.authority :as authority]))

(defn- rel
  "Relations from `{id {:parent p :agents #{…} :slug s}}`."
  [rooms]
  {:parent (into {} (keep (fn [[id {:keys [parent]}]] (when parent [id parent]))) rooms)
   :participants (into {} (map (fn [[id {:keys [agents]}]] [id (set agents)])) rooms)
   :slug (into {} (keep (fn [[id {:keys [slug]}]] (when slug [slug id]))) rooms)})

(def ^:private world
  (rel {:home {:agents #{:var} :slug "home"}
        :fork {:parent :home}
        :deep {:parent :fork}
        :shared {:agents #{:var :huginn}}
        :other {:agents #{:huginn} :slug "other"}}))

(def ^:private var-at-home {:agent :var :room :home})

(deftest the-policy
  (let [can (fn [action room] (authority/can? world var-at-home action {:room room}))]
    (testing "its own room: read, write, merge; never discard or delete it"
      (is (every? #(can % :home) [:read :write :merge]))
      (is (not (can :admin :home))))
    (testing "beneath it, transitively: everything"
      (is (every? #(can % :fork) [:read :write :merge :admin]))
      (is (every? #(can % :deep) [:read :write :merge :admin])))
    (testing "where it takes part: read and write, not merge or admin"
      (is (every? #(can % :shared) [:read :write]))
      (is (not-any? #(can % :shared) [:merge :admin])))
    (testing "anything else, and anything unknown, is denied"
      (is (not-any? #(can % :other) [:read :write :merge :admin]))
      (is (not (can :read :nowhere)))
      (is (not (can :fly :home)) "an unknown action"))
    (testing "rooms are named by id, id string or slug"
      (is (can :write "home"))
      (is (can :write ":fork"))
      (is (not (can :write "other"))))
    (testing "decide lists the checks an effect fails"
      (is (= [] (authority/decide world var-at-home {:effect :room/post :resource {:room "fork"}})))
      (is (= [[:write "other"]] (authority/decide world var-at-home {:effect :room/post :resource {:room "other"}})))
      (is (= [[:merge ":shared"]]
             (authority/decide world var-at-home {:effect :room/merge :resource {:room ":shared" :fork "fork"}})))
      (is (= [[:admin "shared"]]
             (authority/decide world var-at-home {:effect :room/write :resource {:room "shared" :parent "home"}}))
          "re-parenting a room into its own tree needs authority over it first")
      (is (= [] (authority/decide world var-at-home {:effect :fs/read :resource {:path "x"}}))
          "effects without a room are not decided here"))))

;; ---------------------------------------------------------------------------
;; Laws
;; ---------------------------------------------------------------------------

(def ^:private agents [:a :b :c])

(def ^:private gen-world
  "A random forest of rooms :r0…:rn (each parent earlier, so no cycles), with
   random participants."
  (gen/let [n (gen/choose 1 8)
            parents (gen/vector (gen/one-of [(gen/return nil) (gen/choose 0 7)]) n)
            members (gen/vector (gen/fmap set (gen/vector (gen/elements agents) 0 3)) n)]
    (rel (into {} (map (fn [i]
                         [(keyword (str "r" i))
                          {:parent (when-let [p (nth parents i)] (when (< p i) (keyword (str "r" p))))
                           :agents (nth members i)}]))
               (range n)))))

(defn- gen-case [world]
  (gen/tuple (gen/elements agents)
             (gen/elements (keys (:participants world)))
             (gen/elements (keys (:participants world)))))

(def ^:private ladder [:admin :merge :write :read])

(defspec permissions-climb-the-ladder 300
  (prop/for-all [[world [agent home room]] (gen/bind gen-world #(gen/tuple (gen/return %) (gen-case %)))]
                (let [can #(authority/can? world {:agent agent :room home} % {:room room})]
      ;; each action implies every weaker one
                  (every? (fn [[stronger weaker]] (or (not (can stronger)) (can weaker)))
                          (partition 2 1 ladder)))))

(defspec more-relations-never-take-a-permission-away 300
  (prop/for-all [[world [agent home room] extra]
                 (gen/bind gen-world #(gen/tuple (gen/return %) (gen-case %) (gen/elements agents)))]
                (let [grown (update-in world [:participants room] conj extra)
                      can (fn [w action] (authority/can? w {:agent agent :room home} action {:room room}))]
                  (every? #(or (not (can world %)) (can grown %)) ladder))))

(defspec authority-carries-down-the-tree 300
  (prop/for-all [[world [agent home room]] (gen/bind gen-world #(gen/tuple (gen/return %) (gen-case %)))]
                (let [child (keyword (str (name room) "-child"))
                      world (-> world (assoc-in [:parent child] room) (assoc-in [:participants child] #{}))
                      can (fn [r action] (authority/can? world {:agent agent :room home} action {:room r}))]
      ;; what an agent may merge into, it may do anything beneath
                  (or (not (can room :merge)) (every? #(can child %) ladder)))))
