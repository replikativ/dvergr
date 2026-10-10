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

(deftest configure-replaces-all-state
  (testing "config user maps normalise to ids and @usernames"
    (al/configure! {:users [{:id 1 :username "alice"} {:id 2} 3 "@carol"]})
    (is (= #{1 "@alice" 2 3 "@carol"} (al/list-users))))
  (testing "strict + empty list denies everyone"
    (al/configure! {:users [] :strict? true})
    (is (false? (al/allowed? {:id 999 :username "outsider"})))
    (is (false? (al/allowed? {:id 1 :username "alice"}))
        "the previous configuration's users are gone"))
  (testing "a later non-strict configuration clears strict mode"
    (al/configure! {:users []})
    (is (true? (al/allowed? {:id 999})))
    (is (true? (al/open?))))
  (testing "a populated list is never open"
    (al/configure! {:users [5]})
    (is (false? (al/open?)))
    (is (false? (al/allowed? {:id 6})))))
