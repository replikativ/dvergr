(ns dvergr.benchmarks.bird-dialect-test
  "BIRD's SQLite SQL in pg-datahike's dialect: each rewrite keeps SQLite's
   meaning and leaves string literals alone. Pure, no data needed."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.bird.dialect :as dialect]))

(deftest sqlite-idioms-in-postgres
  (testing "identifiers: back-quoted and double-quoted, lower-cased"
    (is (= "SELECT \"free meal count (k-12)\" FROM frpm"
           (dialect/to-postgres "SELECT `Free Meal Count (K-12)` FROM frpm"))))
  (testing "string literals are never touched"
    (is (= "SELECT 'It''s `x`' FROM t" (dialect/to-postgres "SELECT 'It''s `x`' FROM t"))))
  (testing "IIF, nested"
    (is (= "SELECT CASE WHEN a > 1 THEN CASE WHEN b THEN 1 ELSE 2 END ELSE 0 END FROM t"
           (dialect/to-postgres "SELECT IIF(a > 1, IIF(b, 1, 2), 0) FROM t"))))
  (testing "STRFTIME on ISO text dates"
    (is (= "SELECT SUBSTR(birthday, 1, 4) FROM p"
           (dialect/to-postgres "SELECT STRFTIME('%Y', birthday) FROM p")))
    (is (= "SELECT strftime('%W', d) FROM p" (dialect/to-postgres "SELECT strftime('%W', d) FROM p"))
        "a format it cannot keep the meaning of passes through, for the report to show"))
  (testing "LIMIT offset, count"
    (is (= "SELECT x FROM t LIMIT 5 OFFSET 10" (dialect/to-postgres "SELECT x FROM t LIMIT 10, 5"))))
  (testing "ORDER BY keeps SQLite's NULL placement"
    (is (= "SELECT x FROM t ORDER BY h DESC NULLS LAST, n NULLS FIRST LIMIT 1"
           (dialect/to-postgres "SELECT x FROM t ORDER BY h DESC, n LIMIT 1")))
    (is (= "SELECT (SELECT y FROM u ORDER BY z ASC NULLS FIRST) FROM t"
           (dialect/to-postgres "SELECT (SELECT y FROM u ORDER BY z ASC) FROM t"))
        "inside a subquery, up to its closing paren"))
  (testing "REAL is double precision"
    (is (= "SELECT CAST(x AS DOUBLE PRECISION) / y FROM t"
           (dialect/to-postgres "SELECT CAST(x AS REAL) / y FROM t")))))
