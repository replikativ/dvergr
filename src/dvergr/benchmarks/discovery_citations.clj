(ns dvergr.benchmarks.discovery-citations
  "Trusted citation checks for the frozen discovery fixture. Candidate receipt
   IDs are claims to verify, never authority to read another Run's artifacts."
  (:require [datahike.api :as d]
            [dvergr.artifact :as artifact]
            [dvergr.io.acquisition :as acquisition]))

(defn verify
  "Verify submitted discoveries against host-owned reference pages and this
   exact room/Run's acquisition receipts. references is {url exact-evidence}.
   This narrow fixture requires the complete evidence sentence, not entailment
   judgments over arbitrary prose. Missing storage is a setup error, not reward 0."
  [room run-id references answer]
  (let [conn (some-> room :store :conn)
        artifacts (some-> room :store :artifacts)]
    (when-not (and conn artifacts (uuid? run-id)
                   (map? references) (<= 1 (count references) 8)
                   (every? string? (keys references))
                   (every? #(and (string? %) (<= 1 (count %) 240)) (vals references)))
      (throw (ex-info "Citation verification requires a Datahike Room, Run and reference pages" {})))
    (let [entries (:alternatives answer)
          shape? (and (map? answer) (= #{:alternatives} (set (keys answer)))
                      (vector? entries) (<= (count entries) 8)
                      (every? #(and (map? %) (= #{:url :receipt :quote} (set (keys %)))
                                    (string? (:url %)) (uuid? (:receipt %))
                                    (string? (:quote %)) (<= (count (:quote %)) 240)) entries)
                      (= (count entries) (count (set (map :url entries))))
                      (= (count entries) (count (set (map :receipt entries)))))
          db @conn
          valid (when shape?
                  (mapv
                   (fn [{:keys [url receipt quote]}]
                     (let [row (d/q '[:find (pull ?e [*]) .
                                      :in $ ?id ?room ?run
                                      :where [?e :acquisition/id ?id]
                                      [?e :acquisition/room-id ?room]
                                      [?e :acquisition/run-id ?run]]
                                    db receipt (:id room) run-id)
                           ref (:acquisition/body-store-ref row)
                           owned? (and (= (:id room) (:acquisition/room-id row))
                                       (= run-id (:acquisition/run-id row)))
                           acquired? (and owned? (= :completed (:acquisition/status row))
                                          (= :get (:acquisition/method row))
                                          (= 200 (:acquisition/http-status row))
                                          (= :captured (:acquisition/capture row))
                                          (uuid? ref)
                                          (= (acquisition/request-key {:url url :method :get})
                                             (:acquisition/request-key row)))
                           ;; Do not dereference artifacts belonging to a different
                           ;; scope, even when a submitted receipt exists.
                           body (when acquired? (:body (artifact/get-value artifacts ref)))]
                       (boolean (and acquired? (contains? references url)
                                     (= (get references url) quote body)))))
                   entries))
          recovered (if shape? (count (filter true? valid)) 0)]
      {:checks {:schema? (boolean shape?)
                :citations? (boolean (and shape? (seq entries) (every? true? valid)))
                :coverage? (boolean (and shape? (= recovered (count references))))}
       :reward (if (and shape? (every? true? valid))
                 (/ recovered (double (count references))) 0.0)})))
