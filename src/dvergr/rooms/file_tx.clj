(ns dvergr.rooms.file-tx
  "Pure metadata helpers shared by canonical file writes and publication."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [geschichte.query :as query]))

(def ^:private refusal-marker (Object.))

(defn validate-basis!
  "Closed HOST DTO for validation against one native repository snapshot.
   Adds a restriction, never authority. No executable callbacks or schema data."
  [{:keys [backend store branch repository ref commit max-tx] :as basis}]
  (when-not (and (map? basis)
                 (= #{:backend :store :branch :repository :ref :commit :max-tx} (set (keys basis)))
                 (keyword? backend) (uuid? store) (keyword? branch) (uuid? repository)
                 (string? ref) (<= 1 (count ref) 512) (str/starts-with? ref "refs/")
                 (not (re-find #"[\p{Cntrl}]" ref))
                 (uuid? commit) (integer? max-tx) (<= 0 max-tx Long/MAX_VALUE))
    (throw (ex-info "Invalid validated file basis" {:type ::invalid-basis})))
  basis)

(defn- snapshot-basis
  [db]
  {:backend (get-in db [:config :store :backend])
   :store (get-in db [:config :store :id]) :branch (get-in db [:config :branch])
   :repository (:geschichte.repo/id (query/repository db))
   :ref (query/current-ref db) :commit (d/commit-id db) :max-tx (:max-tx db)})

(defn capture-basis
  "Capture a fresh Save-time immutable DB after host validation. Not an editor
   lease, permission or check that validation actually occurred."
  [db]
  (validate-basis! (snapshot-basis db)))

(defn assert-basis!
  "Fixed transaction guard. max-tx is still the pre-transaction value inside
   db.fn/call; clocks/content rows may already have changed hash/eid/op-count.
   The commit-id/max-tx pair also fences earlier writes in one writer batch."
  [db expected]
  (validate-basis! expected)
  (when-not (= expected (snapshot-basis db))
    (throw (ex-info "Repository changed after file validation"
                    {:type ::stale-basis ::native-refusal refusal-marker})))
  [])

(defn native-basis-refusal?
  "Internal native-write evidence, not an interceptor-controlled error type.
   Only consult INSIDE the canonical transact catch, never on handler errors."
  [failure]
  (loop [cause failure seen #{}]
    (cond
      (nil? cause) false
      (contains? seen cause) false
      (identical? refusal-marker (::native-refusal (ex-data cause))) true
      :else (recur (.getCause ^Throwable cause) (conj seen cause)))))

(defn ancestors-of [absolute-path]
  (->> (str/split absolute-path #"/") (remove str/blank?) butlast
       (reductions #(str %1 "/" %2) "") rest))

(defn remove-empty-dirs-tx
  "Retract explicit directories that nothing lies beneath, deepest first.
   Evaluate against the transaction snapshot. Files replacing directories and
   directories containing unrelated work are never removed."
  [db dirs]
  (if-not (seq dirs)
    []
    (let [files (map first (d/q '[:find ?p :where [_ :geschichte.work/path ?p]] db))
          explicit (into {} (d/q '[:find ?p ?e :where [?e :geschichte.work-dir/path ?p]] db))]
      (loop [[dir & more] (sort-by count > (map #(subs % 1) dirs)) gone #{} tx []]
        (if-not dir
          tx
          (let [prefix (str dir "/")
                eid (get explicit dir)
                beneath (some #(str/starts-with? % prefix)
                              (concat files (remove gone (keys explicit))))]
            (if (and eid (not beneath))
              (recur more (conj gone dir) (conj tx [:db/retractEntity eid]))
              (recur more gone tx))))))))
