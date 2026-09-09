(ns dvergr.benchmarks.discovery-citations-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.artifact :as artifact]
            [dvergr.benchmarks.discovery-citations :as citations]
            [dvergr.chat.schema :as schema]
            [dvergr.io.acquisition :as acquisition]
            [dvergr.room.store.datahike :as store]))

;; Synthetic businesses, not a real-world competitor list. The failed source
;; and irrelevant page must not receive credit merely because they were fetched.
(def references
  {"https://example.org/aster" "Aster supports persistent agent teams with shared organizational memory."
   "https://example.org/beryl" "Beryl supports human approval of proposed business changes."})

(deftest only-actual-in-scope-captured-sources-earn-credit
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        _ (schema/ensure-full-schema! conn)
        room {:id :discovery-fixture :store (store/make conn)}
        run-id (random-uuid)
        scope {:conn conn :room-id (:id room) :run-id run-id
               :artifacts (:artifacts (:store room))
               :capture-policy {:allowed-origins #{"https://example.org"} :max-bytes 1024}}
        fetch (fn [url status body]
                (let [response (acquisition/record-request!
                                {:url url} (constantly {:status status :body body}))]
                  {:url url :receipt (get-in response [:dvergr/acquisition :id]) :quote body}))]
    (try
      (binding [acquisition/*scope* scope]
        (let [entries (mapv (fn [[url body]] (fetch url 200 body)) references)
              answer {:alternatives entries}
              score #(citations/verify room run-id references %)
              wrong-run (binding [acquisition/*scope* (assoc scope :run-id (random-uuid))]
                          (fetch (:url (first entries)) 200 (:quote (first entries))))
              wrong-room (binding [acquisition/*scope* (assoc scope :room-id :another-room)]
                           (fetch (:url (first entries)) 200 (:quote (first entries))))
              failed (fetch (:url (first entries)) 503 (:quote (first entries)))
              irrelevant (fetch "https://example.org/cinder" 200 "Cinder is a personal autocomplete editor.")]
          (is (= 1.0 (:reward (score answer))))
          (is (every? true? (vals (:checks (score answer)))))
          (is (= 0.5 (:reward (score {:alternatives [(first entries)]}))))
          (is (zero? (:reward (score {:alternatives (conj entries irrelevant)}))))
          (doseq [bad [(assoc (first entries) :receipt (random-uuid))
                       (assoc (first entries) :url "https://example.org/beryl")
                       (assoc (first entries) :quote "Invented superiority.")
                       (assoc (first entries) :receipt "not-a-uuid")
                       wrong-run wrong-room failed irrelevant]]
            (is (zero? (:reward (score {:alternatives [bad]})))))
          (doseq [bad [nil {} {:alternatives entries :extra true}
                       {:alternatives [(first entries) (first entries)]}]]
            (is (zero? (:reward (score bad)))))
          (with-redefs [artifact/get-value (fn [& _] (throw (ex-info "must not read foreign content" {})))]
            (doseq [foreign [wrong-run wrong-room]]
              (is (zero? (:reward (score {:alternatives [foreign]}))))))))
      (finally (d/release conn) (d/delete-database cfg)))))
