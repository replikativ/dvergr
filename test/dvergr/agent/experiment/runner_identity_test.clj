(ns dvergr.agent.experiment.runner-identity-test
  (:require [clojure.java.basis :as basis]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dvergr.agent.experiment.runner :as runner]))

(deftest the-libraries-are-part-of-an-experiment-s-identity
  ;; a resume after a library upgrade folded Attempts of two versions into
  ;; one Scorecard (results-correctness review): their versions are in it now
  (let [v (runner/library-versions)]
    (is (let [source (get v "org.replikativ/spindel")]
          (or (string? source) (string? (:git/sha source)))) (pr-str v))
    (is (string? (get v "org.replikativ/datahike")) (pr-str v))))

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
