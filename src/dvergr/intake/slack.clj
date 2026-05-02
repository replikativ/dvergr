(ns dvergr.intake.slack
  "Slack intake via personal user token.

   Reads channels, DMs, and searches the Clojurians workspace.
   Uses polling — no WebSocket/Socket Mode required.

   Auth: set :slack :token in config.local.edn (xoxp-... user token).

   Required user token scopes (manifest oauth_config.scopes.user):
     channels:history  channels:read   groups:history  groups:read
     im:history        im:read         mpim:history    mpim:read
     search:read       reactions:read
   Note: scope is search:read (not search:messages).

   Recommended poll interval: 5 min (Tier 3 = 50+ req/min, well within limits).

   Tools registered:
     slack_poll         — check all watched channels + DMs for new messages
     slack_search       — fulltext search across workspace
     slack_channel_read — read recent messages in a specific channel
     slack_channels     — list channels by name pattern

   Note: slack_post (chat:write) intentionally omitted — post manually."
  (:require [dvergr.config :as config]
            [dvergr.tools :as tools]
            [dvergr.intake.core :as intake]
            [clojure.string :as str]))

(def ^:private slack-api "https://slack.com/api")

;; ============================================================================
;; HTTP helpers
;; ============================================================================

(defn- auth-headers []
  (when-let [token (config/slack-token)]
    {"Authorization" (str "Bearer " token)}))

(defn- slack-get
  "GET a Slack API method. Returns parsed body or {:error ...}."
  [method & {:keys [params]}]
  (let [resp (intake/fetch-json (str slack-api "/" method)
                                :headers (auth-headers)
                                :query-params params)]
    (if (:error resp)
      resp
      (if (:ok resp)
        resp
        {:error (or (:error resp) "slack_error") :raw resp}))))

;; ============================================================================
;; Polling state
;; ============================================================================

(def ^:private last-seen
  "Map of channel-id -> Slack timestamp string of last seen message.
   Persists in memory; resets on daemon restart (intentional — just re-fetches
   recent history on first poll after restart)."
  (atom {}))

(defn- ts-mins-ago
  "Slack timestamp string for N minutes ago."
  [n]
  (format "%.6f" (/ (- (System/currentTimeMillis) (* n 60 1000)) 1000.0)))

(defn reset-last-seen!
  "Reset polling state. Useful for testing or forcing a full re-read."
  ([] (reset! last-seen {}))
  ([channel-id] (swap! last-seen dissoc channel-id)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn list-channels
  "List channels of given types matching an optional name pattern.
   types: comma-separated string — public_channel, private_channel, im, mpim.
   Returns vec of {:id :name :topic :num-members :type}."
  [& {:keys [pattern limit types]
      :or {limit 200 types "public_channel,private_channel"}}]
  (loop [cursor nil acc []]
    (let [params (cond-> {:limit (min limit 200) :exclude_archived true :types types}
                   cursor (assoc :cursor cursor))
          resp   (slack-get "conversations.list" :params params)]
      (if (:error resp)
        acc
        (let [channels (mapv (fn [c]
                               {:id          (:id c)
                                :name        (or (:name c)
                                                 ;; DMs have no name — use user ID
                                                 (str "dm:" (get-in c [:user] "?")))
                                :topic       (get-in c [:topic :value])
                                :num-members (:num_members c)
                                :type        (cond (:is_im c)   :dm
                                                   (:is_mpim c) :group-dm
                                                   (:is_private c) :private
                                                   :else        :public)})
                             (:channels resp))
              matched  (if pattern
                         (filter #(str/includes? (:name %) pattern) channels)
                         channels)
              acc'     (into acc matched)
              next-cur (get-in resp [:response_metadata :next_cursor])]
          (if (and (seq next-cur) (< (count acc') limit))
            (recur next-cur acc')
            acc'))))))

(defn channel-history
  "Fetch messages from a channel since `oldest` timestamp.
   Returns vec of {:ts :user :text :thread-ts :reply-count} or {:error ...}."
  [channel-id & {:keys [limit oldest] :or {limit 100}}]
  (let [params (cond-> {:channel channel-id :limit limit}
                 oldest (assoc :oldest oldest))
        resp   (slack-get "conversations.history" :params params)]
    (if (:error resp)
      resp
      (mapv (fn [m]
              {:ts          (:ts m)
               :user        (:user m)
               :text        (:text m)
               :thread-ts   (:thread_ts m)
               :reply-count (:reply_count m 0)
               :reactions   (mapv :name (:reactions m))})
            (:messages resp)))))

(defn search-messages
  "Fulltext search across all accessible channels.
   Returns vec of {:ts :channel :user :text :permalink}."
  [query & {:keys [count sort] :or {count 20 sort "timestamp"}}]
  (let [resp (slack-get "search.messages"
                        :params {:query query :count count :sort sort})]
    (if (:error resp)
      resp
      (mapv (fn [m]
              {:ts         (:ts m)
               :channel    (get-in m [:channel :name])
               :channel-id (get-in m [:channel :id])
               :user       (:username m)
               :text       (:text m)
               :permalink  (:permalink m)})
            (get-in resp [:messages :matches])))))

(defn poll-new-messages!
  "Fetch messages posted since the last poll across the given channels.
   channel-ids: vec of channel IDs to poll.
   interval-mins: how far back to look on first poll (default 5).
   Updates last-seen state. Returns {:channel-id [{msg}...] ...} with only
   channels that have new messages."
  [channel-ids & {:keys [interval-mins] :or {interval-mins 5}}]
  (reduce
    (fn [acc channel-id]
      (let [oldest  (or (get @last-seen channel-id)
                        (ts-mins-ago interval-mins))
            msgs    (channel-history channel-id :oldest oldest :limit 100)]
        (if (or (:error msgs) (empty? msgs))
          acc
          (do
            ;; messages come newest-first; first element is most recent
            (swap! last-seen assoc channel-id (:ts (first msgs)))
            (assoc acc channel-id msgs)))))
    {}
    channel-ids))

;; ============================================================================
;; Formatting helpers
;; ============================================================================

(defn- format-messages [msgs channel-name]
  (str/join "\n\n"
    (map (fn [m]
           (str (when channel-name (str "#" channel-name " | "))
                "@" (or (:user m) "?")
                " [" (:ts m) "]"
                (when (pos? (or (:reply-count m) 0))
                  (str " (" (:reply-count m) " replies)"))
                "\n" (:text m)
                (when (:permalink m) (str "\n" (:permalink m)))))
         msgs)))

(defn- format-poll-results [results channel-map]
  (if (empty? results)
    "No new messages."
    (str/join "\n\n---\n\n"
      (map (fn [[ch-id msgs]]
             (let [ch-name (or (:name (get channel-map ch-id)) ch-id)]
               (str "**" ch-name "** (" (count msgs) " new)\n\n"
                    (format-messages msgs ch-name))))
           results))))

;; ============================================================================
;; Tool registration
;; ============================================================================

(tools/register!
  {:name "slack_poll"
   :description "Check watched Slack channels and DMs for new messages since the last poll.
Call this on a schedule (every 5 min) to reactively monitor activity.
Pass channel IDs to watch (get from slack_channels). On first call for a channel,
returns messages from the last `interval-mins` minutes (default 5).
Subsequent calls return only messages newer than the previous poll.

Returns new messages grouped by channel. Empty result means no new activity.
Use this to:
- Find mentions of datahike / stratum / replikativ
- Spot questions you could answer
- Catch DMs that need a response"
   :parameters {:type "object"
                :properties
                {:channel-ids   {:type "array" :items {:type "string"}
                                 :description "Channel IDs to poll (required)"}
                 :interval-mins {:type "integer"
                                 :description "Minutes back to look on first poll (default 5)"}}
                :required ["channel-ids"]}
   :handler (fn [{:keys [channel-ids interval-mins]}]
              (let [results     (poll-new-messages! channel-ids
                                                    :interval-mins (or interval-mins 5))
                    ;; Build channel-id->name map for formatting
                    channel-map (into {}
                                  (map (juxt :id identity))
                                  (list-channels :types "public_channel,private_channel,im,mpim"
                                                 :limit 500))]
                {:result (format-poll-results results channel-map)
                 :data   results
                 :new-count (reduce + 0 (map (comp count val) results))}))})

(tools/register!
  {:name "slack_channels"
   :description "List Slack channels (and DMs) matching an optional name pattern.
Returns channel IDs needed for slack_poll and slack_channel_read.
Use types param to include DMs: \"public_channel,private_channel,im,mpim\"
Example: {} returns all public/private channels."
   :parameters {:type "object"
                :properties {:pattern {:type "string"
                                       :description "Substring to filter channel names"}
                             :types   {:type "string"
                                       :description "Channel types: public_channel,private_channel,im,mpim (default: public_channel,private_channel)"}
                             :limit   {:type "integer"
                                       :description "Max channels to return (default 200)"}}
                :required []}
   :handler (fn [{:keys [pattern limit types]}]
              (let [channels (list-channels :pattern pattern
                                            :limit (or limit 200)
                                            :types (or types "public_channel,private_channel"))]
                {:result (if (empty? channels)
                           "No channels found."
                           (str/join "\n"
                             (map #(str (case (:type %)
                                          :dm       "DM"
                                          :group-dm "GM"
                                          "#")
                                        (:name %) " [" (:id %) "]"
                                        (when (seq (:topic %))
                                          (str " — " (:topic %))))
                                  channels)))
                 :data channels}))})

(tools/register!
  {:name "slack_channel_read"
   :description "Read recent messages from a specific Slack channel (non-polling).
Requires channel ID (get from slack_channels). Does NOT update poll state.
Use slack_poll for scheduled monitoring; use this for ad-hoc deep reads.
Options: limit (default 50), oldest (unix timestamp string)."
   :parameters {:type "object"
                :properties {:channel-id {:type "string"
                                          :description "Channel ID e.g. C123ABC"}
                             :limit      {:type "integer"
                                          :description "Number of messages (default 50)"}
                             :oldest     {:type "string"
                                          :description "Slack timestamp — only return messages after this"}}
                :required ["channel-id"]}
   :handler (fn [{:keys [channel-id limit oldest]}]
              (let [msgs (channel-history channel-id :limit (or limit 50) :oldest oldest)]
                (if (:error msgs)
                  {:result (str "Error: " (:error msgs))}
                  {:result (format-messages msgs nil)
                   :data   msgs})))})

(tools/register!
  {:name "slack_search"
   :description "Fulltext search across all Slack channels and DMs (requires search:read scope).
This is the most powerful way to find mentions of specific projects or topics.
Searches everything your account can see, including private channels and DMs.
Options: count (default 20), sort: timestamp or score."
   :parameters {:type "object"
                :properties {:query {:type "string"
                                     :description "Search query"}
                             :count {:type "integer"
                                     :description "Max results (default 20)"}
                             :sort  {:type "string"
                                     :description "Sort by: timestamp (recent first) or score (relevance)"}}
                :required ["query"]}
   :handler (fn [{:keys [query count sort]}]
              (let [results (search-messages query
                                             :count (or count 20)
                                             :sort (or sort "timestamp"))]
                (if (:error results)
                  {:result (str "Error: " (:error results))}
                  {:result (if (empty? results)
                             (str "No results for: " query)
                             (format-messages results nil))
                   :data results})))})
