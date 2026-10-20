(ns dvergr.catalog.immutable
  "HOST storage projections of one immutable native repository; no authority,
   selected filesystem, checker execution, or launch routing."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.catalog.room :as room]
            [dvergr.rooms.files :as files]
            [geschichte.content :as content]
            [jsonista.core :as json]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]))

(def max-files 500)
(def max-bytes (* 2 1024 1024))
(def max-output-bytes (* 2 1024 1024))
(def max-output-nodes 20000)
(def max-output-depth 32)

(defn- refuse! []
  (throw (ex-info "Configured catalog projection is unavailable" {:type ::unavailable})))

(defn snapshot-in
  "Read the WHOLE bounded /workflows subtree from one native DB. Validate ALL
   selected metadata before the first body. DB is not a GC/authorization lease."
  [db]
  (when-not (files/immutable-workspace-db? db) (refuse!))
  (when (> (count (take (inc files/max-read-index-entries)
                        (d/datoms db :avet :geschichte.work/path)))
           files/max-read-index-entries)
    (refuse!))
  ;; Worktree's convenience query inner-joins metadata: incomplete path rows
  ;; disappear from it. Inspect EVERY indexed path in this bounded subtree so
  ;; corrupt source cannot silently become a smaller, apparently valid bundle.
  (let [rows (into [] (remove #(= "workflows0" (:v %)))
                   ;; Datahike's end value is inclusive. Exclude ONLY that
                   ;; known outside-prefix endpoint AFTER bounded acquisition.
                   (take (inc max-files)
                         (d/index-range db {:attrid :geschichte.work/path
                                            :start "workflows/" :end "workflows0"})))
        _ (when (> (count rows) max-files) (refuse!))
        entries (reduce (fn [entries row]
                          (let [path (:v row)
                                entity (d/entity db (:e row))]
                            (when-not (and (string? path) (<= (count path) 4096)
                                           (str/starts-with? path "workflows/")
                                           (= path (:geschichte.work/path entity))
                                           (not (contains? entries path)))
                              (refuse!))
                            (assoc entries path
                                   {:content (:geschichte.content/id (:geschichte.work/content entity))
                                    :size (:geschichte.work/size entity)
                                    :mode (:geschichte.work/mode entity)})))
                        (sorted-map) rows)]
    (doseq [[path {:keys [content size mode]}] entries]
      (when-let [[_ name] (and (string? path)
                               (re-matches #"workflows/([^/]+)/workflow\.edn" path))]
        (when-not (re-matches #"[a-z0-9][a-z0-9-]{0,63}" name) (refuse!)))
      (when-not (and (string? path) (<= (count path) 4096)
                     (= (str "/" path) (files/workspace-path (str "/" path)))
                     (uuid? content) (integer? size) (<= 0 size max-bytes)
                     (integer? mode) (= 0100000 (bit-and mode 0170000))
                     (= size (:geschichte.content/size (d/entity db [:geschichte.content/id content]))))
        (refuse!)))
    (when (> (reduce + 0 (map (comp :size val) entries)) max-bytes) (refuse!))
    (let [frozen (atom db)]
      (into (sorted-map)
            (map (fn [[path {:keys [content size]}]]
                   (let [^bytes bytes (content/read-by-id frozen content)]
                     (when-not (and bytes (= size (alength bytes))) (refuse!))
                     [path (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                                           (.onMalformedInput CodingErrorAction/REPORT)
                                           (.onUnmappableCharacter CodingErrorAction/REPORT))
                                         (ByteBuffer/wrap bytes)))])))
            entries))))

(defn- bounded-output! [rows]
  ;; Iterative traversal bounds JSON representation depth/nodes BEFORE encoder
  ;; recursion. EDN params are data, not evaluated or silently truncated.
  (loop [pending [[rows 0]] nodes 0]
    (when (> nodes max-output-nodes) (refuse!))
    (when-let [[value depth] (peek pending)]
      (when (> depth max-output-depth) (refuse!))
      (let [pending (pop pending)
            child-count (cond (map? value) (* 2 (count value))
                              (coll? value) (count value)
                              :else 0)
            _ (when (> (+ nodes 1 (count pending) child-count) max-output-nodes) (refuse!))
            children (cond (map? value) (mapcat identity value)
                           (coll? value) value
                           :else nil)]
        (recur (into pending (map #(vector % (inc depth))) children) (inc nodes)))))
  (when (> (alength ^bytes (json/write-value-as-bytes rows)) max-output-bytes) (refuse!))
  rows)

(defn list-in
  "Descriptions from ONE pinned whole subtree. Original room handle is DISPLAY
   provenance only, NOT an executable generic catalog_start/check reference.
   Portable bundle IDs remain content-only. Promotion tier is current trusted
   host metadata, not part of this DB snapshot. Structural invalidity alone
   yields an inert malformed row; storage/tier failures propagate."
  [db original-room target-stamp]
  (when-not (and (string? original-room) (<= 1 (count original-room) 1024)
                 (string? target-stamp) (<= 1 (count target-stamp) 256)) (refuse!))
  (let [snapshot (snapshot-in db)
        names (->> (keys snapshot)
                   (keep #(second (re-matches #"workflows/([a-z0-9][a-z0-9-]{0,63})/workflow\.edn" %)))
                   sort vec)]
    (bounded-output!
     (mapv (fn [name]
             (let [prefix (str "workflows/" name "/")
                   source (into (sorted-map)
                                (keep (fn [[path text]]
                                        (when (str/starts-with? path prefix)
                                          [(subs path (count prefix)) text]))) snapshot)
                   bundle (try
                            (let [b (room/bundle name source)]
                              (when (m/validate room/Definition (:definition b)) b))
                            (catch clojure.lang.ExceptionInfo error
                              (if (= ::room/invalid-bundle (:type (ex-data error))) nil (throw error))))
                   row {:id (str original-room "/" name) :room original-room
                        :target-stamp target-stamp}]
               (if bundle
                 (merge row (select-keys (:definition bundle) [:title :doc :params :profile])
                        {:bundle (str (:id bundle)) :verifier (clojure.core/name (room/tier bundle))})
                 (assoc row :problems ["not well formed; source inspection required"]))))
           names))))
