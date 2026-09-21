(ns dvergr.sandbox.malli-test
  "malli in the agent sandbox: compiled validation, `m/=>` registering into
   the world's state (so it forks with the world, never into malli's host
   global), withheld global mutators, and schemas rendered by `sandbox/doc`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [malli.core :as m]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- eval-in [sci-ctx world code]
  (let [r (sandbox/eval-code sci-ctx code :execution-context world)]
    (if (:success r) (:value r) (throw (ex-info (str "eval failed: " (get-in r [:error :message])) r)))))

(defn- error-of [sci-ctx world code]
  (get-in (sandbox/eval-code sci-ctx code :execution-context world) [:error :message]))

(def ^:private annotated
  (str "(require '[malli.core :as m] '[malli.error :as me])"
       "(m/=> available-count [:=> [:cat [:map [\"variants\" [:map-of :string [:map [\"available\" :boolean]]]]]] :int])"
       "(defn available-count [p] (count (filter #(get % \"available\") (vals (get p \"variants\")))))"))

(deftest malli-validates-and-registers-per-world
  (let [ec (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (testing "compiled malli works from agent code"
        (is (true? (eval-in sci-ctx ec "(malli.core/validate [:map [:x :int]] {:x 1})")))
        (is (= {"available" ["should be a boolean"]}
               (eval-in sci-ctx ec "(malli.error/humanize (malli.core/explain [:map [\"available\" :boolean]] {\"available\" \"yes\"}))")))
        (is (= [:map ["a" :int]] (eval-in sci-ctx ec "(malli.provider/provide [{\"a\" 1}])"))))
      (testing "m/=> registers in the sandbox's world, not in malli's host global"
        (eval-in sci-ctx ec annotated)
        (is (= 1 (eval-in sci-ctx ec "(available-count {\"variants\" {\"1\" {\"available\" true} \"2\" {\"available\" false}}})")))
        (is (= [:=> [:cat [:map ["variants" [:map-of :string [:map ["available" :boolean]]]]]] :int]
               (eval-in sci-ctx ec "(m/form (get-in (m/function-schemas) ['user 'available-count :schema]))")))
        (is (false? (eval-in sci-ctx ec "(m/validate (first (m/children (get-in (m/function-schemas) ['user 'available-count :schema]))) [{\"variants\" [1 2]}])"))
            "the args schema rejects a vector where an id-keyed map is required")
        (is (nil? (get-in (m/function-schemas) ['user 'available-count])) "host registry untouched"))
      (testing "an invalid schema fails at m/=>"
        (is (some? (error-of sci-ctx ec "(m/=> broken [:=> :not-a-schema])"))))
      (testing "global mutators are withheld"
        (is (some? (error-of sci-ctx ec "(malli.core/-register-function-schema! 'user 'x [:=> [:cat] :int] {})")))
        (is (some? (error-of sci-ctx ec "(malli.registry/set-default-registry! {})"))))
      (testing "sandbox/doc renders m/=> and :malli/schema metadata"
        (eval-in sci-ctx ec "(defn shout {:doc \"Upper-case.\" :malli/schema [:=> [:cat :string] :string]} [s] (clojure.string/upper-case s))")
        (let [doc (eval-in sci-ctx ec "(sandbox/doc 'user)")]
          (is (str/includes? doc "schema: [:=> [:cat [:map"))
          (is (str/includes? doc "schema: [:=> [:cat :string] :string]"))))
      (finally (ctx/stop-context! ec)))))

(deftest registrations-fork-with-the-world
  (let [parent (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session parent)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx parent)
      (eval-in sci-ctx parent annotated)
      (let [child (ctx/fork-context parent :mode :frozen)
            registered (fn [world] (eval-in sci-ctx world "(set (keys (get (malli.core/function-schemas) 'user)))"))]
        (try
          (eval-in sci-ctx child "(m/=> only-in-child [:=> [:cat :int] :int])")
          (is (= '#{available-count only-in-child} (registered child)) "the child inherits and extends")
          (is (= '#{available-count} (registered parent)) "the parent never sees the child's registration")
          (finally (ctx/stop-context! child))))
      (finally (ctx/stop-context! parent)))))
