(ns dvergr.intake.mail
  "Email intake via briefkasten local sync.

   Reads from the locally-synced briefkasten store (no live IMAP connection).
   Call (sync-mail!) periodically to pull new messages from the server.

   Tools registered:
     mail_inbox   — list recent INBOX messages
     mail_search  — fulltext search across all mail
     mail_read    — read a full message (body from EML file)
     mail_sync    — pull new messages from IMAP server"
  (:require [dvergr.config :as config]
            [dvergr.tools :as tools]
            [org.replikativ.briefkasten.core :as bk]
            [clojure-mail.message]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [javax.mail Session]
           [javax.mail.internet MimeMessage]
           [java.util Properties]))

;; ============================================================================
;; Account Management
;; ============================================================================

(def ^:private accounts (atom {}))

(defn- make-bk-config [account-id]
  (let [acct-cfg (config/mail-account account-id)]
    (when-not acct-cfg
      (throw (ex-info (str "No mail config for " account-id " in config.local.edn")
                      {:account account-id})))
    (assoc acct-cfg :id account-id)))

(defn get-account!
  "Get or create a briefkasten account handle.
   Lazily initialised on first use."
  [account-id]
  (or (get @accounts account-id)
      (let [acct (bk/create-account! (make-bk-config account-id))]
        (swap! accounts assoc account-id acct)
        acct)))

(defn default-account! []
  (get-account! :datahike-contact))

;; ============================================================================
;; Body Extraction
;; ============================================================================

(defn- read-body-from-eml
  "Read plain text body from a stored EML file."
  [eml-path]
  (when (and eml-path (.exists (io/file eml-path)))
    (try
      (let [session (Session/getDefaultInstance (Properties.))
            mime    (MimeMessage. session (io/input-stream (io/file eml-path)))
            parsed  (clojure-mail.message/read-message mime)
            body    (:body parsed)]
        (cond
          (string? body) body
          (sequential? body)
          (or (some #(when (and (map? %)
                                (str/starts-with? (str (:content-type %)) "text/plain"))
                       (:body %))
                    body)
              (some #(when (map? %) (:body %)) body))
          (map? body) (:body body)
          :else (str body)))
      (catch Exception e
        (str "[body read error: " (.getMessage e) "]")))))

(defn- ns-msg->plain
  "Convert datahike namespace-qualified message map to plain keys."
  [m]
  (when m
    {:uid         (:mail.message/uid m)
     :subject     (:mail.message/subject m)
     :from        (:mail.message/from m)
     :to          (:mail.message/to m)
     :date        (:mail.message/date m)
     :flags       (:mail.message/flags m)
     :folder      (:mail.message/folder m)
     :size        (:mail.message/size m)
     :eml-path    (:mail.message/eml-path m)
     :message-id  (:mail.message/message-id m)}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn list-inbox
  "List recent INBOX messages (no body — fast, from datahike).
   Returns vec of {:uid :subject :from :date :flags}."
  [& {:keys [account limit] :or {account :datahike-contact limit 20}}]
  (let [acct (get-account! account)
        msgs (bk/list-messages acct "INBOX" :limit limit)]
    (mapv (comp #(select-keys % [:uid :subject :from :date :flags])
                ns-msg->plain)
          msgs)))

(defn search-mail
  "Fulltext search across all synced mail.
   Returns vec of {:score :uid :folder :subject :from :date}."
  [query & {:keys [account limit] :or {account :datahike-contact limit 10}}]
  (let [acct (get-account! account)]
    (bk/search acct query :limit limit)))

(defn read-message
  "Read a full message including body text.
   lookup: {:folder \"INBOX\" :uid 4}"
  [{:keys [folder uid] :as lookup} & {:keys [account] :or {account :datahike-contact}}]
  (let [acct (get-account! account)
        msg  (ns-msg->plain (bk/read-message acct lookup))]
    (when msg
      (assoc msg :body-text (read-body-from-eml (:eml-path msg))))))

(defn sync-inbox!
  "Pull new messages from IMAP server for one account.
   Returns sync result map per folder."
  [& {:keys [account folders] :or {account :datahike-contact}}]
  (let [acct (get-account! account)]
    (bk/sync! acct :folders (or folders ["INBOX"]))))

;; ============================================================================
;; Formatting helpers
;; ============================================================================

(defn- format-message-list [msgs]
  (if (empty? msgs)
    "No messages."
    (str/join "\n"
      (map-indexed (fn [i m]
                     (str (inc i) ". "
                          "[" (:uid m) "] "
                          (:from m) "\n"
                          "   " (:subject m) "\n"
                          "   " (:date m)
                          (when (seq (:flags m))
                            (str " " (str/join " " (map name (:flags m)))))))
                   msgs))))

;; ============================================================================
;; Tool registration
;; ============================================================================

(tools/register!
  {:name "mail_inbox"
   :description "List recent messages in the datahike.io contact inbox (local cache).
Returns subject, from, date, flags. Does not fetch body. Use mail_read for full content.
Options: :limit (default 20)."
   :parameters {:type "object"
                :properties {:limit {:type "integer" :description "Max messages to return (default 20)"}}
                :required []}
   :handler (fn [{:keys [limit]}]
              (try
                (let [msgs (list-inbox :limit (or limit 20))]
                  {:result (format-message-list msgs)
                   :data   msgs})
                (catch Exception e
                  {:result (str "Mail inbox unavailable: " (.getMessage e))
                   :error true})))})

(tools/register!
  {:name "mail_search"
   :description "Fulltext search across all synced datahike.io mail.
Returns matching messages with relevance scores. Useful for finding specific threads,
senders, or topics. Options: :limit (default 10)."
   :parameters {:type "object"
                :properties {:query {:type "string" :description "Search query"}
                             :limit {:type "integer" :description "Max results (default 10)"}}
                :required ["query"]}
   :handler (fn [{:keys [query limit]}]
              (let [results (search-mail query :limit (or limit 10))]
                {:result (if (empty? results)
                           (str "No results for: " query)
                           (str/join "\n"
                             (map #(str "Score " (format "%.2f" (:score %))
                                        " [" (:folder %) " uid:" (:uid %) "] "
                                        (:from %) "\n"
                                        "  " (:subject %))
                                  results)))
                 :data results}))})

(tools/register!
  {:name "mail_read"
   :description "Read the full body of a specific email message.
Requires the folder name and UID (get these from mail_inbox or mail_search).
Example: {:folder \"INBOX\" :uid 4}"
   :parameters {:type "object"
                :properties {:folder {:type "string" :description "Folder name e.g. INBOX"}
                             :uid    {:type "integer" :description "Message UID"}}
                :required ["folder" "uid"]}
   :handler (fn [{:keys [folder uid]}]
              (let [msg (read-message {:folder folder :uid uid})]
                (if msg
                  {:result (str "From: " (:from msg) "\n"
                                "Subject: " (:subject msg) "\n"
                                "Date: " (:date msg) "\n"
                                "---\n"
                                (:body-text msg))
                   :data msg}
                  {:result (str "Message not found: " folder " uid " uid)})))})

(tools/register!
  {:name "mail_sync"
   :description "Pull new messages from the IMAP server into local cache.
Run this to check for mail since the last sync. Syncs INBOX by default.
Returns count of new messages stored per folder."
   :parameters {:type "object"
                :properties {:folders {:type "array" :items {:type "string"}
                                       :description "Folders to sync (default: [\"INBOX\"])"}}
                :required []}
   :handler (fn [{:keys [folders]}]
              (try
                (let [result (sync-inbox! :folders folders)]
                  {:result (str "Sync complete:\n"
                                (str/join "\n"
                                  (map (fn [[folder r]]
                                         (str "  " folder ": "
                                              (:stored r 0) " new, "
                                              (:errors r 0) " errors"))
                                       result)))
                   :data result})
                (catch Exception e
                  {:result (str "Mail sync failed: " (.getMessage e))
                   :error true})))})
