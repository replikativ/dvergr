(ns dvergr.agent.conversation-capture
  "Bounded immutable working-conversation capture; no IO, authority or live fallback."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.artifact :as artifact]))

(def max-bytes (* 2 1024 1024))
(def max-messages 4096)
(def max-nodes 100000)
(def version 1)

(defn- invalid! [message]
  (throw (ex-info message {:type ::invalid-conversation})))

(defn- portable! [value]
  (loop [pending [[value 0]] nodes 0 characters 0]
    (when (seq pending)
      (when (or (>= nodes max-nodes) (> characters max-bytes))
        (invalid! "Conversation exceeds structural limit"))
      (let [[x depth] (peek pending)
            tail (pop pending)]
        (when (> depth 48) (invalid! "Conversation exceeds nesting limit"))
        (cond
          (record? x) (invalid! "Conversation contains a runtime record")
          (or (map? x) (vector? x) (list? x) (set? x))
          (let [children (if (map? x) (mapcat identity x) x)
                bounded (vec (take (inc max-nodes) children))]
            (when (> (+ nodes (count tail) (count bounded)) max-nodes)
              (invalid! "Conversation exceeds structural limit"))
            (recur (into tail (map #(vector % (inc depth))) bounded)
                   (inc nodes) characters))
          (or (string? x) (keyword? x) (symbol? x))
          (let [length (if (string? x) (count x)
                           (+ (count (name x)) (count (namespace x)) 2))
                cost (+ characters (* 6 length))]
            (when (> cost max-bytes) (invalid! "Conversation exceeds pre-encoding limit"))
            (recur tail (inc nodes) cost))
          (or (nil? x) (boolean? x) (uuid? x) (inst? x)
              (and (integer? x) (<= Long/MIN_VALUE x Long/MAX_VALUE))
              (and (or (instance? Double x) (instance? Float x))
                   (Double/isFinite (double x))))
          (recur tail (inc nodes) (+ characters 64))
          :else (invalid! "Conversation contains unsupported nonportable data"))))))

(def ^:private message-keys
  #{:message/id :message/chat :message/role :message/content :message/created-at
    :message/compacted? :message/reasoning :message/tokens :message/important?
    :message/summary :message/tool-uses :message/tool-use-id :message/turn-number
    :message/pruned? :message/original-tokens :message/reply-to :db/doc})

(defn- stable-lookup? [attribute value]
  (and (vector? value) (= 2 (count value))
       (= attribute (first value)) (uuid? (second value))))

(defn- validate-message-fields! [message]
  ;; These are semantic values, never Datahike-local entity IDs or pulled
  ;; component facades. Keep the actual message factory's stable lookup form.
  (doseq [[field predicate]
          {:db/doc string?
           :message/chat #(stable-lookup? :chat/id %)
           :message/reply-to #(stable-lookup? :message/id %)
           :message/created-at inst?
           :message/reasoning string?
           :message/summary string?
           :message/tool-use-id string?
           :message/compacted? boolean?
           :message/important? boolean?
           :message/pruned? boolean?
           :message/tokens #(and (integer? %) (<= 0 % Long/MAX_VALUE))
           :message/original-tokens #(and (integer? %) (<= 0 % Long/MAX_VALUE))
           :message/turn-number #(and (integer? %) (<= 0 % Long/MAX_VALUE))}]
    (when (and (contains? message field) (not (predicate (get message field))))
      (invalid! "Unsupported conversation message field value"))))

(defn validate-messages! [messages schemas]
  (when-not (and (vector? messages) (<= 1 (count messages) max-messages))
    (invalid! "Conversation must be a bounded nonempty vector"))
  (portable! [messages schemas])
  (when-not (and (map? schemas)
                 (every? (fn [[name definition]]
                           (and (string? name) (map? definition)
                                (= #{:name :parameters} (set (keys definition)))
                                (= name (:name definition))
                                (map? (:parameters definition))))
                         schemas))
    (invalid! "Invalid captured tool definitions"))
  (doseq [message messages]
    (when-not (and (map? message) (uuid? (:message/id message))
                   (contains? #{:system :user :assistant :tool-result} (:message/role message))
                   (or (nil? (:message/content message)) (string? (:message/content message))))
      (invalid! "Invalid conversation message"))
    (when (some #(and (keyword? %) (str/starts-with? (name %) "attachment-")) (keys message))
      (invalid! "Attachment retention is not supported by conversation capture v1"))
    (when-not (every? message-keys (keys message))
      (invalid! "Unknown message fields are not supported by conversation capture v1"))
    (validate-message-fields! message)
    (when (and (contains? message :message/tool-uses)
               (not (and (vector? (:message/tool-uses message))
                         (every? #(and (map? %) (string? (:tool-use/id %))
                                       (string? (:tool-use/name %))
                                       (or (nil? (:tool-use/input %)) (map? (:tool-use/input %))))
                                 (:message/tool-uses message)))))
      (invalid! "Conversation contains unresolved structured tool data"))
    (doseq [use (:message/tool-uses message)]
      (when-not (every? #{:tool-use/id :tool-use/name :tool-use/input} (keys use))
        (invalid! "Unknown tool-use fields are not portable"))
      (when-let [input (:tool-use/input use)]
        (let [definition (get schemas (:tool-use/name use))]
          (when-not (and definition
                         (= input (tool-schema/tool-input->entity
                                   definition
                                   (tool-schema/entity->input input (:tool-use/name use) []))))
            (invalid! "Tool input is not a complete typed semantic value"))))))
  (when-not (= (count messages) (count (set (map :message/id messages))))
    (invalid! "Conversation message identities must be unique"))
  (let [calls (vec (map :tool-use/id (mapcat :message/tool-uses messages)))
        results (mapv :message/tool-use-id (filter #(= :tool-result (:message/role %)) messages))]
    (when-not (and (every? string? results)
                   (= (count calls) (count (set calls)))
                   (= (count results) (count (set results)))
                   (= (set calls) (set results)))
      (invalid! "Only complete, unambiguous tool/result boundaries may be captured")))
  messages)

(defn manifest [prepared]
  {:version version :codec :edn-v1 :ref (:ref prepared)
   :bytes (:size prepared) :messages (count (get-in prepared [:value :messages]))})

(defn prepare
  ([messages] (prepare messages {}))
  ([messages tools]
   (let [messages (vec (take (inc max-messages) messages))
         _ (when (> (count messages) max-messages) (invalid! "Too many conversation messages"))
         _ (portable! messages)
         names (set (map :tool-use/name (mapcat :message/tool-uses messages)))
         schemas (into {} (keep (fn [name]
                                  (when-let [definition (get tools name)]
                                    [name (select-keys definition [:name :parameters])]))) names)
         messages (validate-messages! messages schemas)
         value {:version version :messages messages :tool-schemas schemas}
         prepared (artifact/prepare-value value max-bytes)]
    ;; Reject unreadable symbols, unsupported numbers and lossy/custom values
    ;; before publishing any promise of resumability.
     (when-not (= value (edn/read-string (String. ^bytes (:bytes prepared) "UTF-8")))
       (invalid! "Conversation does not round-trip through the artifact codec"))
     prepared)))

(defn validate-manifest! [m]
  (when-not (and (= #{:version :codec :ref :bytes :messages} (set (keys m)))
                 (= version (:version m)) (= :edn-v1 (:codec m))
                 (uuid? (:ref m)) (integer? (:bytes m)) (<= 1 (:bytes m) max-bytes)
                 (integer? (:messages m)) (<= 1 (:messages m) max-messages))
    (invalid! "Unsupported or invalid conversation manifest"))
  m)

(defn restore [artifacts m]
  (validate-manifest! m)
  (let [loaded (artifact/get-bounded-value artifacts (:ref m) (:bytes m))
        value (:value loaded)]
    (when-not (and loaded (= (:bytes m) (:size loaded))
                   (= #{:version :messages :tool-schemas} (set (keys value)))
                   (= version (:version value)))
      (invalid! "Conversation artifact is missing or mismatched"))
    (validate-messages! (:messages value) (:tool-schemas value))
    (when-not (= (:messages m) (count (:messages value)))
      (invalid! "Conversation count does not match its manifest"))
    (:messages value)))
