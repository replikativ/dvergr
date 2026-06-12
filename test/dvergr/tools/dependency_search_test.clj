(ns dvergr.tools.dependency-search-test
  "Tests for dependency search tool."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.tools.dependency-search :as dep-search]))

(deftest ^:integration search-clojars-test       ; hits the live Clojars API
  (testing "Search Clojars for buddy libraries"
    (let [results (dep-search/search-clojars "buddy")]
      (is (vector? results))
      (is (pos? (count results)))
      (is (<= (count results) 5))  ; Top 5 results max
      (let [first-result (first results)]
        (is (= :clojars (:source first-result)))
        (is (string? (:group first-result)))
        (is (string? (:name first-result)))
        (is (string? (:coordinate first-result)))
        (is (string? (:version first-result)))
        (is (string? (:url first-result))))))

  (testing "Search for nonexistent library returns empty or error"
    (let [results (dep-search/search-clojars "zzznonexistent12345xyz")]
      (is (vector? results))
      ;; A nonexistent lib yields empty results or an error entry — never a real hit.
      (is (or (empty? results) (contains? (first results) :error))
          "nonexistent lib → empty or error"))))

(deftest ^:integration search-maven-central-test  ; hits the live Maven Central API
  (testing "Search Maven Central for jackson libraries"
    (let [results (dep-search/search-maven-central "jackson-core")]
      (is (vector? results))
      (is (pos? (count results)))
      (is (<= (count results) 5))  ; Top 5 results max
      (let [first-result (first results)]
        (is (= :maven-central (:source first-result)))
        (is (string? (:group first-result)))
        (is (string? (:name first-result)))
        (is (string? (:coordinate first-result)))
        (is (string? (:version first-result)))
        (is (string? (:url first-result))))))

  (testing "Search for Java library (Gson)"
    (let [results (dep-search/search-maven-central "gson")]
      (is (vector? results))
      (is (pos? (count results))))))

(deftest ^:integration search-dependency-combined-test  ; hits Clojars + Maven Central
  (testing "Search both sources for buddy"
    (let [results (dep-search/search-dependency "buddy" :all)]
      (is (map? results))
      (is (contains? results :clojars))
      (is (contains? results :maven-central))
      (is (vector? (:clojars results)))
      (is (vector? (:maven-central results)))))

  (testing "Search only Clojars"
    (let [results (dep-search/search-dependency "buddy" :clojars)]
      (is (map? results))
      (is (contains? results :clojars))
      (is (not (contains? results :maven-central)))))

  (testing "Search only Maven Central"
    (let [results (dep-search/search-dependency "jackson" :maven-central)]
      (is (map? results))
      (is (contains? results :maven-central))
      (is (not (contains? results :clojars))))))

(deftest format-search-results-test
  (testing "Format results for display"
    (let [results {:clojars [{:coordinate "buddy/buddy-core"
                              :version "1.11.423"
                              :description "Security library"
                              :url "https://clojars.org/buddy/buddy-core"}]
                   :maven-central [{:coordinate "com.google.code.gson/gson"
                                    :version "2.10.1"
                                    :description "JSON library"
                                    :url "https://search.maven.org/artifact/com.google.code.gson/gson"}]}
          formatted (dep-search/format-search-results results)]
      (is (string? formatted))
      (is (.contains formatted "Clojars Results"))
      (is (.contains formatted "Maven Central Results"))
      (is (.contains formatted "buddy/buddy-core"))
      (is (.contains formatted "gson"))))

  (testing "Format empty results"
    (let [results {:clojars []
                   :maven-central []}
          formatted (dep-search/format-search-results results)]
      (is (string? formatted))
      (is (.contains formatted "No results found")))))

(deftest search-dependency-tool-def-test          ; pure — no network
  (testing "Tool definition structure"
    (is (= "search_dependency" (:name dep-search/search-dependency-tool)))
    (is (string? (:description dep-search/search-dependency-tool)))
    (is (map? (:parameters dep-search/search-dependency-tool)))
    (is (fn? (:execute dep-search/search-dependency-tool)))))

(deftest ^:integration search-dependency-tool-execute-test  ; runs the tool → live HTTP
  (testing "Execute tool with buddy query"
    (let [result ((:execute dep-search/search-dependency-tool)
                  {:query "buddy" :source "clojars"}
                  {})]
      (is (= :success (:type result)))
      (is (string? (:content result)))
      (is (map? (:metadata result)))
      (is (vector? (get-in result [:metadata :clojars])))))

  (testing "Execute tool with all sources"
    (let [result ((:execute dep-search/search-dependency-tool)
                  {:query "ring"}
                  {})]
      (is (= :success (:type result)))
      (is (map? (:metadata result)))
      (is (contains? (:metadata result) :clojars))
      (is (contains? (:metadata result) :maven-central)))))
