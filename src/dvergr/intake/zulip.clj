(ns dvergr.intake.zulip
  "Zulip intake — fetch messages and search across Clojurians Zulip streams.

   Uses the Zulip REST API with Basic auth (bot email + API key).
   Configuration via :zulip key in config.local.edn:

     :zulip {:email   \"dvergr-bot@zulipchat.com\"
             :api-key \"YOUR_API_KEY\"
             :site    \"https://clojurians.zulipchat.com\"}"
  (:require [dvergr.config :as config]
            [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Zulip API
;; ============================================================================

(defn- zulip-config []
  (get (config/config) :zulip))

(defn- zulip-get
  "GET a Zulip API endpoint with Basic auth. Returns parsed JSON or {:error ...}."
  [path & {:keys [query-params]}]
  (let [{:keys [email api-key site]} (zulip-config)]
    (when-not (and email api-key site)
      (throw (ex-info "Zulip not configured. Add :zulip {:email :api-key :site} to config.local.edn" {})))
    (try
      (let [url  (str site "/api/v1" path)
            resp (http/get url
                           {:headers          {"User-Agent" "dvergr/1.0 (intake)"}
                            :basic-auth       {:user email :pass api-key}
                            :query-params     query-params
                            :throw-exceptions? false
                            :as               :string
                            :connect-timeout  15000})]
        (if (<= 200 (:status resp) 299)
          (json/read-value (:body resp) json/keyword-keys-object-mapper)
          {:error (str "Zulip HTTP " (:status resp) ": "
                       (some-> (:body resp)
                               (json/read-value json/keyword-keys-object-mapper)
                               :msg))}))
      (catch Exception e
        {:error (.getMessage e)}))))

;; ============================================================================
;; Data Access
;; ============================================================================

(defn fetch-streams
  "List public Zulip streams (channels). Returns vec of stream maps or {:error ...}."
  []
  (let [data (zulip-get "/streams")]
    (if (:error data)
      data
      (->> (:streams data)
           (filter #(not (:invite_only %)))
           (mapv (fn [s]
                   {:name        (:name s)
                    :stream-id   (:stream_id s)
                    :description (:description s)
                    :subscribers (:subscribers s)}))))))

(defn fetch-topics
  "List topics in a stream. Returns vec of topic maps or {:error ...}."
  [stream-id]
  (let [data (zulip-get (str "/users/me/" stream-id "/topics"))]
    (if (:error data)
      data
      (->> (:topics data)
           (mapv (fn [t]
                   {:name       (:name t)
                    :max-id     (:max_id t)}))))))

(defn- parse-message [msg]
  {:id        (:id msg)
   :sender    (:sender_full_name msg)
   :stream    (:display_recipient msg)
   :topic     (:subject msg)
   :content   (:content msg)
   :timestamp (:timestamp msg)
   :url       (str (:site (zulip-config))
                    "/#narrow/stream/" (java.net.URLEncoder/encode (str (:display_recipient msg)) "UTF-8")
                    "/topic/" (java.net.URLEncoder/encode (str (:subject msg)) "UTF-8")
                    "/near/" (:id msg))})

(defn fetch-messages
  "Fetch recent messages from a stream, optionally filtered by topic.
   Returns vec of message maps or {:error ...}."
  [stream & {:keys [topic count] :or {count 20}}]
  (let [narrow (cond-> [{"operator" "channel" "operand" stream}]
                 topic (conj {"operator" "topic" "operand" topic}))
        data   (zulip-get "/messages"
                          :query-params {"anchor"         "newest"
                                         "num_before"     (str count)
                                         "num_after"      "0"
                                         "narrow"         (json/write-value-as-string narrow)
                                         "apply_markdown" "false"})]
    (if (:error data)
      data
      (->> (:messages data)
           (mapv parse-message)))))

(defn search-messages
  "Search messages across all streams. Returns vec of message maps or {:error ...}."
  [query & {:keys [stream count] :or {count 20}}]
  (let [narrow (cond-> [{"operator" "search" "operand" query}]
                 stream (conj {"operator" "channel" "operand" stream}))
        data   (zulip-get "/messages"
                          :query-params {"anchor"         "newest"
                                         "num_before"     (str count)
                                         "num_after"      "0"
                                         "narrow"         (json/write-value-as-string narrow)
                                         "apply_markdown" "false"})]
    (if (:error data)
      data
      (->> (:messages data)
           (mapv parse-message)))))

