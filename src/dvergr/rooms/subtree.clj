(ns dvergr.rooms.subtree
  "Fixed bounded metadata preconditions, not authority or an executable hook."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [geschichte.query :as query]))

(def max-entries 200)
(def max-file-bytes (* 8 1024 1024))
(def max-total-bytes (* 16 1024 1024))
(def max-index-rows 20000)

(defn- refuse! [reason]
  (throw (ex-info "Workspace subtree changed or is invalid"
                  {:type ::conflict :reason reason})))

(defn- path? [path]
  (and (string? path) (<= 1 (count path) 512)
       (not (str/starts-with? path "/"))
       (not (re-find #"[\\\p{Cntrl}]" path))
       (every? #(and (not (str/blank? %)) (not (#{"." ".." ".git"} %)))
               (str/split path #"/" -1))))

(defn- entry? [entry]
  (and (map? entry) (= #{:content :size :mode} (set (keys entry)))
       (uuid? (:content entry)) (integer? (:size entry))
       (<= 0 (:size entry) max-file-bytes) (integer? (:mode entry)) (#{33188 33261} (:mode entry))))

(defn validate!
  "Closed prefix/relative-manifest DTO; valid for empty initial/final trees."
  [{:keys [prefix entries] :as condition}]
  (when-not (and (map? condition) (= #{:prefix :entries} (set (keys condition)))
                 (string? prefix) (<= 3 (count prefix) 512)
                 (str/starts-with? prefix "/") (str/ends-with? prefix "/")
                 (path? (subs prefix 1 (dec (count prefix))))
                 (map? entries) (<= (count entries) max-entries)
                 (every? (fn [[path entry]]
                           (and (path? path) (<= (+ (count prefix) (count path)) 512) (entry? entry))) entries)
                 (<= (reduce + 0 (map (comp :size val) entries)) max-total-bytes))
    (refuse! :invalid-condition))
  condition)

(defn prefix-tree!
  "Read EVERY bounded indexed path row, including malformed rows an inner join
   would hide. Pure immutable metadata only. Deleted index rows are validated
   but omitted from ordinary tree semantics; tombstones have a separate cap."
  [db prefix stage?]
  (let [stem (subs prefix 1)
        end (str (subs stem 0 (dec (count stem))) "0")
        attr (if stage? :geschichte.stage/path :geschichte.work/path)
        limit (if stage? max-index-rows max-entries)
        bounded (vec (take (inc limit)
                           (d/index-range db {:attrid attr :start stem :end end})))
        ;; Datahike's end is inclusive. Exclude ONLY this known successor
        ;; after the bounded take, never hide arbitrary malformed path rows.
        rows (filterv #(not= end (:v %)) bounded)]
    (when (> (count rows) limit) (refuse! :path-index-limit))
    (let [tree
          (reduce
           (fn [{:keys [seen tree]} row]
             (let [path (:v row) entity (d/entity db (:e row))
                   actual (get entity attr)
                   relative (when (and (string? path) (str/starts-with? path stem))
                              (subs path (count stem)))
                   state (when stage? (:geschichte.stage/state entity))
                   entry (if stage?
                           {:content (:geschichte.content/id (:geschichte.stage/content entity))
                            :size (:geschichte.stage/size entity) :mode (:geschichte.stage/mode entity)}
                           {:content (:geschichte.content/id (:geschichte.work/content entity))
                            :size (:geschichte.work/size entity) :mode (:geschichte.work/mode entity)})]
               (when-not (and (= actual path) (path? path) (path? relative)
                              (not (contains? seen path))
                              (or (not stage?) (#{:present :deleted} state)))
                 (refuse! :invalid-path-row))
               (if (= :deleted state)
                 (do (when-not (every? nil? (vals entry)) (refuse! :invalid-deletion-row))
                     {:seen (conj seen path) :tree tree})
                 (do (when-not (and (entry? entry)
                                    (= (:size entry) (:geschichte.content/size
                                                      (d/entity db [:geschichte.content/id (:content entry)]))))
                       (refuse! :invalid-entry-row))
                     (when (>= (count tree) max-entries) (refuse! :entry-limit))
                     {:seen (conj seen path) :tree (assoc tree relative entry)}))))
           {:seen #{} :tree (sorted-map)} rows)]
      (:tree tree))))

(defn assert-before! [db condition]
  (validate! condition)
  (when-not (= (:entries condition) (prefix-tree! db (:prefix condition) false))
    (refuse! :before-mismatch))
  condition)

(defn assert-after! [db condition]
  (validate! condition)
  (when-not (and (= (:entries condition) (prefix-tree! db (:prefix condition) false))
                 (= (:entries condition) (prefix-tree! db (:prefix condition) true)))
    (refuse! :after-mismatch))
  condition)

(defn- raw-entry [db path stage?]
  (let [path-attr (if stage? :geschichte.stage/path :geschichte.work/path)
        entity (d/entity db [path-attr path])]
    (when (:db/id entity)
      (cond-> (if stage?
                {:content (:geschichte.content/id (:geschichte.stage/content entity))
                 :size (:geschichte.stage/size entity) :mode (:geschichte.stage/mode entity)}
                {:content (:geschichte.content/id (:geschichte.work/content entity))
                 :size (:geschichte.work/size entity) :mode (:geschichte.work/mode entity)})
        stage? (assoc :state (:geschichte.stage/state entity))))))

(defn stage-rows [db absolute-paths]
  (into {} (map (fn [path] [(subs path 1) (raw-entry db (subs path 1) true)])) absolute-paths))

(defn work-rows [db absolute-paths]
  (into {} (map (fn [path] [(subs path 1) (raw-entry db (subs path 1) false)])) absolute-paths))

(defn- normalized [entry stage?]
  (cond
    (nil? entry) nil
    (not stage?) entry
    (= :present (:state entry)) (dissoc entry :state)
    (and (= :deleted (:state entry)) (every? nil? (vals (dissoc entry :state)))) nil
    :else ::invalid-entry))

(defn- entry-tx [db path value stage?]
  (let [path-attr (if stage? :geschichte.stage/path :geschichte.work/path)
        content-attr (if stage? :geschichte.stage/content :geschichte.work/content)
        size-attr (if stage? :geschichte.stage/size :geschichte.work/size)
        mode-attr (if stage? :geschichte.stage/mode :geschichte.work/mode)
        eid (:db/id (d/entity db [path-attr path]))]
    (cond
      (= value (raw-entry db path stage?)) []
      (nil? value) (if eid [[:db/retractEntity eid]] [])
      (and stage? (= :deleted (:state value)))
      (cond-> [{path-attr path :geschichte.stage/state :deleted}]
        eid (into (mapv #(vector :db/retract eid %) [content-attr size-attr mode-attr])))
      :else [(cond-> {path-attr path content-attr [:geschichte.content/id (:content value)]
                      ;; DTO integer values may be boxed Integer (alength).
                      ;; Native Geschichte scalar attributes require Long.
                      size-attr (long (:size value)) mode-attr (long (:mode value))}
               stage? (assoc :geschichte.stage/state :present))])))

(defn- assert-target! [db basis]
  (let [plan (:plan basis)]
    (when-not (and (= (:repository plan) (:geschichte.repo/id (query/repository db)))
                   (= (:ref plan) (query/current-ref db))
                   (= (:workspace plan)
                      {:store (select-keys (get-in db [:config :store]) [:backend :id])
                       :branch (get-in db [:config :branch])}))
      (refuse! :target-changed))))

(defn stage-tx
  "Fixed atomic stage CAS: original clean index prefix and independent AFTER
   worktree are checked before staging ONLY the submitted paths."
  [db basis after paths]
  (assert-target! db basis)
  (validate! after)
  (let [projected (reduce (fn [tree absolute]
                            (when-not (str/starts-with? absolute (:prefix after)) (refuse! :stage-path-escape))
                            (let [relative (subs absolute (count (:prefix after)))]
                              (if-let [entry (get (:entries after) relative)]
                                (assoc tree relative entry) (dissoc tree relative))))
                          (:index-prefix basis) paths)]
    (when-not (= projected (:entries after)) (refuse! :unsubmitted-index-difference)))
  (when-not (and (= (:prefix (:before basis)) (:prefix after))
                 (= (:index-prefix basis) (prefix-tree! db (:prefix after) true))
                 (= (:entries after) (prefix-tree! db (:prefix after) false)))
    (refuse! :stage-basis-changed))
  (vec (mapcat (fn [absolute]
                 (when-not (str/starts-with? absolute (:prefix after)) (refuse! :stage-path-escape))
                 (let [path (subs absolute 1)
                       entry (get (:entries after) (subs absolute (count (:prefix after))))]
                   (entry-tx db path (if entry (assoc entry :state :present)
                                         (when (raw-entry db path true) {:state :deleted :content nil :size nil :mode nil}))
                             true))) paths)))

(defn undo-data
  "Metadata value CAS, not history-sensitive ownership. Work and index restore
   independently ONLY when FULL current entry equals independent native AFTER.
   An index restore additionally requires this extent's original stage ACK."
  [db basis after touched stage-owned? paths]
  (assert-target! db basis)
  (let [prefix (:prefix after)
        changes
        (for [stage? [false true]
              absolute (if stage? (when stage-owned? paths) touched)
              :let [path (subs absolute 1) relative (subs absolute (count prefix))
                    current (raw-entry db path stage?)
                    desired (if stage? (get (:stage-rows basis) path)
                                (get-in basis [:before :entries relative]))
                    owned (get (:entries after) relative)]]
          (if (= (normalized current stage?) owned)
            {:tx (entry-tx db path desired stage?)}
            {:conflict absolute}))]
    {:tx (vec (mapcat :tx changes)) :conflicts (vec (sort (set (keep :conflict changes))))}))
