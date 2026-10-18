(ns dvergr.agent.output
  "Scoped reads of deliberately published, immutable evaluation outputs.
   Certification evidence is private by default; only :agent/output is public."
  (:require [dvergr.agent.authority :as authority]
            [dvergr.agent.run :as run]
            [dvergr.room.store :as store]))

(defn- refuse! [message type]
  (throw (ex-info message {:type type})))

(defn- scoped-run! [room scope id]
  (authority/require-scoped-run! room scope id))

(defn- bounded-data! [value]
  ;; Stop before traversing an entire oversized value. Never print handles or
  ;; accept arbitrary host objects; published output must remain portable data.
  (let [nodes (volatile! 0) chars (volatile! 0)]
    (letfn [(visit [x depth]
              (when (or (> depth 32) (> (vswap! nodes inc) 2048))
                (refuse! "Published output is too large; select a smaller :path" ::too-large))
              (cond
                (map? x) (into {} (map (fn [[k v]] [(visit k (inc depth)) (visit v (inc depth))])) x)
                (vector? x) (mapv #(visit % (inc depth)) x)
                (set? x) (into #{} (map #(visit % (inc depth))) x)
                (list? x) (apply list (map #(visit % (inc depth)) x))
                (or (nil? x) (boolean? x) (string? x) (number? x)
                    (keyword? x) (symbol? x) (uuid? x))
                (do (when (> (vswap! chars + (if (string? x) (count x) (count (str x)))) 16384)
                      (refuse! "Published output is too large; select a smaller :path or string chunk" ::too-large))
                    (if (instance? clojure.lang.IObj x) (with-meta x nil) x))
                :else (refuse! "Published output contains unsupported runtime data" ::unsupported)))]
      (visit value 0))))

(defn- string-chunk [^String value offset length]
  (let [size (count value)
        split-pair? (fn [i]
                      (and (< 0 i size)
                           (Character/isHighSurrogate (.charAt value (dec (int i))))
                           (Character/isLowSurrogate (.charAt value (int i)))))]
    (when (or (> offset size) (split-pair? offset))
      (refuse! "Offset is outside the published string or splits a Unicode character" ::invalid-options))
    (let [requested-end (+ offset (min length (- size offset)))
          end (if (split-pair? requested-end) (dec requested-end) requested-end)]
      (when (and (< offset size) (= offset end))
        (refuse! "Chunk length is too small for the next Unicode character" ::invalid-options))
      (cond-> {:output (subs value (int offset) (int end))
               :encoding :utf-16 :offset offset :size size}
        (< end size) (assoc :next-offset end)))))

(defn read-output
  "Read only :agent/output in a certified descendant Attempt's evidence.
   opts: :path (map keys/vector indices, max 16), :offset/:length for strings
   (UTF-16 code units, max 16384 per chunk). Non-string values are bounded to
   2048 nodes, depth 32 and 16384 scalar characters. Successful child reads
   record a durable causal input on the live consumer Run. Never reads a world."
  ([room scope id] (read-output room scope id {}))
  ([room scope id {:keys [path offset length] :or {path [] offset 0 length 16384} :as opts}]
   (when-not (and (map? opts) (every? #{:path :offset :length} (keys opts))
                  (vector? path) (<= (count path) 16)
                  (every? #(or (keyword? %) (and (string? %) (<= (count %) 2048))
                               (and (integer? %) (<= 0 % Long/MAX_VALUE))) path)
                  (integer? offset) (<= 0 offset Long/MAX_VALUE)
                  (integer? length) (<= 1 length 16384))
     (refuse! "Invalid published-output read options" ::invalid-options))
   (scoped-run! room scope id)
   (let [room-store (:store room)
         attempt (when (satisfies? store/PAttemptStore room-store)
                   (store/-load-attempt room-store (store/conversation-id room) id))]
     (when-not attempt
       (refuse! "No certified Attempt for this Run" ::not-certified))
     (when-not (contains? (:attempt/evidence attempt) :agent/output)
       (refuse! "This evaluator did not publish agent-readable output" ::not-published))
     (let [value (reduce (fn [value key]
                           (when-not (and (or (map? value) (vector? value))
                                          (contains? value key))
                             (refuse! "No such published output path" ::missing-path))
                           (get value key))
                         (get-in attempt [:attempt/evidence :agent/output]) path)
           result (merge (select-keys attempt [:attempt/id :attempt/run-id :attempt/content-id
                                               :attempt/evidence-content-id])
                         {:path path}
                         (if (string? value)
                           (string-chunk value offset length)
                           (do (when (or (contains? opts :offset) (contains? opts :length))
                                 (refuse! "Chunk options require a string output" ::invalid-options))
                               {:output (bounded-data! value)})))]
       (when-not (= scope id) (run/record-cause! scope id))
       result))))
