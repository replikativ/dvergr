(ns dvergr.security.allowlist-test
  "The Telegram access allowlist: empty-set policy (open vs strict), membership,
   and entry validation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.security.allowlist :as al]))

;; Reset the global state around each test (it's a defonce atom).
(use-fixtures :each
  (fn [t]
    (al/set-users! [])
    (al/set-strict! false)
    (t)
    (al/set-users! [])
    (al/set-strict! false)))

(deftest empty-allowlist-policy
  (testing "empty + default (non-strict) → open access (backwards compatible)"
    (al/set-users! [])
    (is (true? (al/allowed? {:id 42 :username "anyone"}))))
  (testing "empty + strict → fail-closed (deny everyone)"
    (al/set-users! [])
    (al/set-strict! true)
    (is (false? (al/allowed? {:id 42 :username "anyone"})))))

(deftest membership
  (al/set-users! [123 "@bob"])
  (testing "numeric id match" (is (true? (al/allowed? {:id 123}))))
  (testing "@username match"  (is (true? (al/allowed? {:username "bob"}))))
  (testing "non-member denied even though the set is non-empty"
    (is (false? (al/allowed? {:id 999 :username "mallory"}))))
  (testing "a populated allowlist denies regardless of strict flag"
    (al/set-strict! false)
    (is (false? (al/allowed? {:id 999})))))

(deftest entry-validation
  (testing "valid entries: numeric id, @username"
    (is (= #{7 "@alice"} (do (al/set-users! [7 "@alice"]) (al/list-users)))))
  (testing "invalid entries are rejected"
    (is (thrown? clojure.lang.ExceptionInfo (al/add-user! {:bad :map})))
    (is (thrown? clojure.lang.ExceptionInfo (al/add-user! "no-at-prefix")))
    (is (thrown? clojure.lang.ExceptionInfo (al/set-users! [7 :keyword])))))
