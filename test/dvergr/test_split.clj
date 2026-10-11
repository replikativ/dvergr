(ns dvergr.test-split
  "Run one share of the test namespaces, for CI's parallel test containers.

   CI lists every test namespace (`all`), CircleCI's `tests split` hands each
   container its share (`mine`), and this kaocha hook narrows the suites to
   that share before anything loads, so a container compiles only the
   namespaces it runs. Without the environment variables nothing changes.

   So that no test is lost, the hook refuses to run unless `all` is exactly
   the set of namespaces kaocha itself finds in the suites, and every
   namespace of `mine` is in it. `tests split` puts each line of `all` into
   exactly one container, so together the containers run every namespace
   once.

     DVERGR_TEST_ALL_NAMESPACES  file: every test namespace, one per line
     DVERGR_TEST_NAMESPACES      file: this container's share"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaocha.load :as load]))

(defn- read-names [path]
  (->> (str/split-lines (slurp (io/file path)))
       (map str/trim)
       (remove str/blank?)
       (map symbol)
       set))

(defn- discovered
  "The namespaces kaocha's suites load, found as kaocha finds them."
  [config]
  (set (for [suite (:kaocha/tests config)
             ns-sym (load/find-test-nss (:kaocha/test-paths suite)
                                        (map re-pattern (:kaocha/ns-patterns suite)))]
         ns-sym)))

(defn- only-pattern [names]
  (str "^(?:" (str/join "|" (map #(java.util.regex.Pattern/quote (str %)) (sort names))) ")$"))

(defn pre-load
  "Kaocha hook: narrow every suite to this container's namespaces."
  [config]
  (let [mine-file (System/getenv "DVERGR_TEST_NAMESPACES")
        all-file (System/getenv "DVERGR_TEST_ALL_NAMESPACES")]
    (if-not mine-file
      config
      (let [found (discovered config)
            all (read-names all-file)
            mine (read-names mine-file)]
        (when (not= found all)
          (throw (ex-info (str "The namespace list does not match the test namespaces kaocha finds."
                               " Missing from the list: " (sort (set/difference found all))
                               "; not test namespaces: " (sort (set/difference all found)))
                          {:missing (set/difference found all)
                           :extra (set/difference all found)})))
        (when-let [unknown (seq (set/difference mine all))]
          (throw (ex-info (str "This container's share names unknown namespaces: " (sort unknown))
                          {:unknown (set unknown)})))
        (println (format "[test-split] this container runs %d of %d test namespaces"
                         (count mine) (count all)))
        (update config :kaocha/tests
                (fn [suites]
                  (mapv #(assoc % :kaocha/ns-patterns [(only-pattern mine)]) suites)))))))
