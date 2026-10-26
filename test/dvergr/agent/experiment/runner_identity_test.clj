(ns dvergr.agent.experiment.runner-identity-test
  (:require [clojure.java.basis :as basis]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dvergr.agent.experiment.runner :as runner]))

(deftest the-libraries-are-part-of-an-experiment-s-identity
  ;; a resume after a library upgrade folded Attempts of two versions into
  ;; one Scorecard (results-correctness review): their versions are in it now
  (let [v (runner/library-versions)]
    (doseq [lib ["org.replikativ/spindel" "org.replikativ/datahike"]]
      (if (get-in (basis/current-basis) [:libs (symbol lib) :local/root])
        (is (not (contains? v lib))
            "an unversioned local checkout must not borrow stale packaging metadata")
        (is (let [source (get v lib)]
              (or (string? source) (string? (:git/sha source)))) (pr-str v))))))

(deftest resolved-datahike-git-sha-not-cache-location-identifies-execution
  (let [first-sha "1234567890123456789012345678901234567890"
        next-sha "abcdefabcdefabcdefabcdefabcdefabcdefabcd"
        identity-at (fn [cache sha]
                      (with-redefs [basis/current-basis
                                    (constantly {:libs {'org.replikativ/datahike
                                                        {:git/url "https://example.test/datahike.git"
                                                         :git/sha sha
                                                         :deps/root (str cache "/libs/org.replikativ/datahike/" sha)}}})]
                        (runner/library-versions)))]
    (is (= (identity-at "/ci/cache" first-sha) (identity-at "/dev/cache" first-sha)))
    (is (not= (identity-at "/ci/cache" first-sha) (identity-at "/ci/cache" next-sha)))))

(deftest resolved-source-wins-over-packaging-metadata
  (let [git {:git/url "https://example.test/spindel.git"
             :git/sha "1234567890123456789012345678901234567890"
             :deps/root "/cache/libs/org.replikativ/spindel/1234567890123456789012345678901234567890"}]
    (with-redefs [basis/current-basis (constantly {:libs {'org.replikativ/spindel git
                                                          'org.replikativ/datahike {:mvn/version "resolved"}
                                                          'org.replikativ/kontor {:local/root "/work/kontor"}}})
                  io/resource (fn [& _] (throw (ex-info "must not inspect stale packaging metadata" {})))]
      (is (= {"org.replikativ/spindel" (dissoc git :deps/root) "org.replikativ/datahike" "resolved"}
             (runner/library-versions))))
    (with-redefs [basis/current-basis (constantly {:libs {'org.replikativ/spindel
                                                          (assoc (dissoc git :deps/root)
                                                                 :git/sha "abcdefabcdefabcdefabcdefabcdefabcdefabcd")}})]
      (is (not= (dissoc git :deps/root) (get (runner/library-versions) "org.replikativ/spindel"))))))

(deftest git-checkout-location-is-not-experiment-identity
  (let [sha "1234567890123456789012345678901234567890"
        identity-at (fn [prefix suffix]
                      (with-redefs [basis/current-basis
                                    (constantly {:libs {'org.replikativ/spindel
                                                        {:git/url "https://example.test/spindel.git"
                                                         :git/sha sha
                                                         :deps/root (str prefix "/libs/org.replikativ/spindel/" sha suffix)}}})]
                        (get (runner/library-versions) "org.replikativ/spindel")))]
    (is (= (identity-at "/ci/cache" "") (identity-at "/dev/cache" "")))
    (is (= "modules/runtime" (:deps/root (identity-at "/ci/cache" "/modules/runtime"))))
    (is (= (str "modules/" sha) (:deps/root (identity-at "/ci/cache" (str "/modules/" sha)))))
    (is (not= (identity-at "/ci/cache" "/modules/runtime")
              (identity-at "/ci/cache" "/modules/other")))))
