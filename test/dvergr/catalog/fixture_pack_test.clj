(ns dvergr.catalog.fixture-pack-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.catalog.fixture-pack :as pack]
            [dvergr.catalog.room :as room]
            [dvergr.catalog.workspace :as workspace]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [hasch.core :as hasch]))

(defn- packed [files]
  (pr-str {:kind :dvergr/fixture-pack :version 1 :files files}))

(def base {"workflow.edn" "{:title \"Read\" :task \"Read the input\"}"
           "checker.clj" "(ns check) (defn check [_] {:checks {:ok true} :reward 1.0})"})

(deftest packs-preserve-exact-text-and-raw-source-identity
  (let [text "\uFEFF---\r\nsimmis-id: 14b5b3e2-e641-489f-a7e9-246f40c7a312\r\n---\r\n未来 λ 🦋\n"
        source (assoc base "fixture-pack.edn" (packed {"/wiki/Company.md" text}))
        b (room/bundle "read" source)]
    (is (empty? (room/check-files source)))
    (is (= {"/wiki/Company.md" text} (:fixtures b)))
    (is (= (seq (.getBytes text "UTF-8"))
           (seq (.getBytes ^String (get-in b [:fixtures "/wiki/Company.md"]) "UTF-8"))))
    (is (= source (:files b)))
    (is (= (hasch/uuid [:dvergr/room-workflow source]) (:id b)))
    (is (not (contains? (:fixtures b) "/fixture-pack.edn")))
    (is (empty? (:cases b)))))

(deftest case-packs-retain-existing-shared-overrides-and-held-gold
  (let [source (assoc base "fixtures/shared.txt" "shared"
                      "fixtures/overridden.txt" "old"
                      "cases/one/fixture-pack.edn" (packed {"/overridden.txt" "new" "/only-one.md" "one"})
                      "cases/one/gold.edn" "{:secret \"held gold\"}"
                      "cases/two/fixture-pack.edn" (packed {"/only-two.md" "two"}))
        b (room/bundle "read" source)]
    (is (= {"/shared.txt" "shared" "/overridden.txt" "old"} (:fixtures b)))
    (is (= {"/overridden.txt" "new" "/only-one.md" "one"} (get-in b [:cases "one" :fixtures])))
    (is (= {"/only-two.md" "two"} (get-in b [:cases "two" :fixtures])))
    (is (= {:secret "held gold"} (get-in b [:cases "one" :gold])))
    (is (= "new" (get (merge (:fixtures b) (get-in b [:cases "one" :fixtures])) "/overridden.txt")))
    (is (not (contains? (get-in b [:cases "one" :fixtures]) "/gold.edn"))))
  (let [source (assoc base "fixtures/input.md" "old style")]
    (is (= {"/input.md" "old style"} (:fixtures (room/bundle "read" source))))))

(deftest invalid-packs-are-refused-during-shape-inspection-including-unselected-cases
  (doseq [text ["{}" "nil" "#unknown/tag {}"
                (str (packed {}) " {}")
                "{:kind :dvergr/fixture-pack :version 2 :files {}}"
                "{:kind :dvergr/fixture-pack :version 1 :files {} :extra true}"
                (packed {"/a.md" 42})
                (packed {"/a.md" (str (char 0xd800))})
                (apply str (repeat (inc pack/max-pack-bytes) " "))]]
    (let [source (assoc base "fixtures/read.txt" "ok" "cases/unselected/fixture-pack.edn" text)]
      (is (seq (room/check-files source)))
      (is (thrown? clojure.lang.ExceptionInfo (room/bundle "read" source)))))
  (doseq [path ["relative.md" "/a/../b" "/a//b" "/a/./b" "/.git/config" "/a\\b" "/" ""]]
    (is (seq (room/check-files (assoc base "fixture-pack.edn" (packed {path "text"})))))))

(deftest native-world-setup-materializes-only-selected-decoded-fixtures
  (let [source (assoc base "fixtures/shared.txt" "shared"
                      "cases/one/fixture-pack.edn" (packed {"/docs/input.md" "selected\r\n未来"})
                      "cases/one/gold.edn" "{:secret \"held\"}"
                      "cases/two/fixture-pack.edn" (packed {"/docs/other.md" "not selected"}))
        b (room/bundle "read" source)
        world (discourse/make-room {:id (keyword (str "fixture-pack-" (random-uuid)))
                                    :store (memory/make)})]
    (try
      ((:prepare (room/world-setup b)) {:room world :environment {:environment/metadata {:case "one"}}})
      (is (= {"/shared.txt" "shared" "/docs/input.md" "selected\r\n未来"}
             (workspace/read-tree world "/" {:strict? true})))
      (finally (discourse/close-room! world)))))

(deftest collisions-and-resource-amplification-are-rejected
  (doseq [[plain encoded] [["fixtures/a.md" "fixture-pack.edn"]
                           ["fixtures//a.md" "fixture-pack.edn"]
                           ["cases/one/fixtures/a.md" "cases/one/fixture-pack.edn"]]]
    (is (seq (room/check-files (assoc base plain "same" encoded (packed {"/a.md" "same"}))))))
  (is (seq (room/check-files (assoc base "fixture-pack.edn"
                                    (packed (into {} (for [i (range 101)] [(str "/" i) "x"])))))))
  (with-redefs [pack/max-decoded-bytes 5]
    (is (seq (room/check-files (assoc base "fixture-pack.edn" (packed {"/a" "1234"})
                                      "cases/one/fixture-pack.edn" (packed {"/b" "5678"})))))))

(deftest bounded-reader-work-precedes-edn-parsing-even-for-discarded-forms
  (let [deep (str (apply str (repeat 5000 "{:a ")) "nil" (apply str (repeat 5000 "}")))
        valid (packed {"/input.md" (apply str (repeat 10000 "{[; #_ ^ \" \\"))})]
    (doseq [text [deep (str "#_" deep " " (packed {}))
                  (str (apply str (repeat 5000 "^:meta ")) (packed {}))
                  (apply str (repeat 10000 "9"))]]
      (is (seq (room/check-files (assoc base "fixtures/ok.txt" "ok"
                                        "cases/unselected/fixture-pack.edn" text)))))
    (is (empty? (room/check-files (assoc base "fixture-pack.edn" valid))))
    (is (empty? (room/check-files (assoc base "fixture-pack.edn" (str "; ignored #_ [ { ^\n" valid)))))))
