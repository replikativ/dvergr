(ns dvergr.channels.email
  "Email channel for dvergr — IMAP read + SMTP send.

   Uses clojure-mail for IMAP reading and postal for SMTP sending.
   Both are lazy-loaded to allow the channel framework to compile
   even if the deps aren't on the classpath yet (agents can add-lib at runtime).

   Usage:
     (require '[dvergr.channels.core :as ch])
     (require '[dvergr.channels.email :as email])

     (def mail (ch/connect!
                 (email/make-email
                   {:imap {:host \"imap.gmail.com\" :port 993
                           :user \"me@gmail.com\" :pass \"app-password\"}
                    :smtp {:host \"smtp.gmail.com\" :port 587
                           :user \"me@gmail.com\" :pass \"app-password\"}})))"
  (:require [dvergr.channels.core :as channels]
            [clojure.string :as str]))

(defn- format-from
  "Format the :from field from clojure-mail (a seq of address maps) to a readable string."
  [from]
  (cond
    (string? from) from
    (sequential? from) (str/join ", " (map (fn [a]
                                             (if (map? a)
                                               (let [{:keys [name address]} a]
                                                 (if name
                                                   (str name " <" address ">")
                                                   address))
                                               (str a)))
                                           from))
    :else (str from)))

;; ============================================================================
;; Lazy loading of email deps (may not be on classpath initially)
;; ============================================================================

(defn- require-mail! []
  (require 'clojure-mail.core)
  (require 'clojure-mail.message))

(defn- require-postal! []
  (require 'postal.core))

;; ============================================================================
;; Rate limiting
;; ============================================================================

(defn- make-rate-limiter
  "Simple token-bucket rate limiter. Returns a function (check-and-consume!)
   that returns true if the action is allowed."
  [max-per-minute]
  (let [state (atom {:tokens max-per-minute
                      :last-refill (System/currentTimeMillis)})]
    (fn check-and-consume! []
      (let [now (System/currentTimeMillis)
            {:keys [tokens last-refill]} @state
            elapsed-ms (- now last-refill)
            refill (int (* (/ elapsed-ms 60000.0) max-per-minute))
            new-tokens (min max-per-minute (+ tokens refill))]
        (if (pos? new-tokens)
          (do (swap! state assoc :tokens (dec new-tokens) :last-refill now)
              true)
          false)))))

;; ============================================================================
;; IMAP operations
;; ============================================================================

(defn- make-imap-session
  "Create a javax.mail.Session with SSL trust properties.
   When :ssl-trust is set (default \"*\"), trusts the specified hosts.
   This is necessary for self-hosted mail servers with Let's Encrypt
   or self-signed certificates."
  [imap-config]
  (let [as-props (ns-resolve 'clojure-mail.core 'as-properties)
        ssl-trust (get imap-config :ssl-trust "*")
        props (as-props
               {"mail.store.protocol"                "imaps"
                "mail.imaps.usesocketchannels"       "true"
                "mail.imaps.ssl.enable"              "true"
                "mail.imaps.ssl.trust"               ssl-trust
                "mail.imaps.ssl.checkserveridentity" "false"})]
    (javax.mail.Session/getInstance props)))

(defn- get-imap-store
  "Get or create an IMAP store connection from channel state."
  [channel]
  (let [state (:state channel)]
    (or (:imap-store @state)
        (let [imap-config (get-in channel [:config :imap])
              store-fn (ns-resolve 'clojure-mail.core 'store)
              session (make-imap-session imap-config)
              server (if (:port imap-config)
                       [(:host imap-config) (:port imap-config)]
                       (:host imap-config))
              store (store-fn "imaps" session server
                             (:user imap-config)
                             (:pass imap-config))]
          (swap! state assoc :imap-store store)
          store))))

(defn- get-messages
  "Get messages from a folder. Uses inbox for INBOX, all-messages for others."
  [store folder-name]
  (let [all-msgs-fn (ns-resolve 'clojure-mail.core 'all-messages)]
    (all-msgs-fn store (or folder-name "INBOX"))))

(defn- list-messages
  "List recent messages from IMAP inbox."
  [channel {:keys [folder limit]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        limit (min (or limit 20) (get-in channel [:config :max-list-results] 50))
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        messages (take limit (get-messages store folder))]
    (mapv (fn [msg]
            (let [m (read-msg msg)]
              {:id (:id m)
               :subject (:subject m)
               :from (format-from (:from m))
               :date (str (:date-sent m))
               :read? (not (:unread? m))}))
          messages)))

(defn- extract-text-body
  "Extract the text/plain body from a clojure-mail message body (seq of parts)."
  [body]
  (cond
    (string? body) body
    (sequential? body)
    (or
     ;; Prefer text/plain
     (some (fn [part]
             (when (and (map? part)
                        (str/starts-with? (str (:content-type part)) "text/plain"))
               (:body part)))
           body)
     ;; Fall back to text/html
     (some (fn [part]
             (when (and (map? part)
                        (str/starts-with? (str (:content-type part)) "text/html"))
               (:body part)))
           body)
     ;; Fall back to first body
     (:body (first body)))
    :else (str body)))

(defn- read-message
  "Read full content of a message by ID."
  [channel {:keys [id]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        messages (get-messages store "INBOX")
        target (first (filter #(= id (:id (read-msg %))) messages))]
    (if target
      (let [m (read-msg target)]
        {:id (:id m)
         :subject (:subject m)
         :from (format-from (:from m))
         :to (format-from (:to m))
         :date (str (:date-sent m))
         :body (extract-text-body (:body m))})
      (throw (ex-info (str "Message not found: " id) {:id id})))))

(defn- search-messages
  "Search emails by subject or from field."
  [channel {:keys [query folder limit]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        limit (min (or limit 20) (get-in channel [:config :max-list-results] 50))
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        pattern (re-pattern (str "(?i)" query))
        messages (get-messages store folder)]
    (->> messages
         (map read-msg)
         (filter (fn [m]
                   (or (and (:subject m) (re-find pattern (:subject m)))
                       (and (:from m) (re-find pattern (str (:from m)))))))
         (take limit)
         (mapv (fn [m]
                 {:id (:id m)
                  :subject (:subject m)
                  :from (format-from (:from m))
                  :date (str (:date-sent m))})))))

;; ============================================================================
;; SMTP operations
;; ============================================================================

(defn- send-email!
  "Send an email via SMTP."
  [channel {:keys [to subject body cc bcc]}]
  (require-postal!)
  (let [smtp-config (get-in channel [:config :smtp])
        send-fn (ns-resolve 'postal.core 'send-message)
        rate-limiter (:send-rate-limiter @(:state channel))]
    (when (and rate-limiter (not (rate-limiter)))
      (throw (ex-info "Send rate limit exceeded. Please wait before sending more." {})))
    (let [ssl-trust (get smtp-config :ssl-trust "*")
          postal-config (cond-> {:host (:host smtp-config)
                                  :port (:port smtp-config)
                                  :user (:user smtp-config)
                                  :pass (:pass smtp-config)
                                  :tls (get smtp-config :tls true)}
                          ;; Trust self-hosted SSL certs (keyword becomes mail.smtp.ssl.trust)
                          ssl-trust (assoc :ssl.trust ssl-trust))
          msg (cond-> {:from (:user smtp-config)
                       :to (if (string? to) [to] to)
                       :subject subject
                       :body body}
                cc (assoc :cc (if (string? cc) [cc] cc))
                bcc (assoc :bcc (if (string? bcc) [bcc] bcc)))
          result (send-fn postal-config msg)]
      (if (= :SUCCESS (:error result))
        {:sent true :message-id (:message-id result)}
        (throw (ex-info (str "Failed to send email: " (:message result))
                        {:result result}))))))

;; ============================================================================
;; IMAP mutations
;; ============================================================================

(defn- mark-read!
  "Mark an email as read."
  [channel {:keys [id]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        messages (get-messages store "INBOX")]
    (if-let [target (first (filter #(= id (:id (read-msg %))) messages))]
      (do
        (.setFlag target javax.mail.Flags$Flag/SEEN true)
        {:marked true :id id})
      (throw (ex-info (str "Message not found: " id) {:id id})))))

(defn- delete-email!
  "Delete an email by ID."
  [channel {:keys [id]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        messages (get-messages store "INBOX")]
    (if-let [target (first (filter #(= id (:id (read-msg %))) messages))]
      (do
        (.setFlag target javax.mail.Flags$Flag/DELETED true)
        {:deleted true :id id})
      (throw (ex-info (str "Message not found: " id) {:id id})))))

(defn- move-email!
  "Move an email to a different folder."
  [channel {:keys [id target-folder]}]
  (require-mail!)
  (let [store (get-imap-store channel)
        read-msg (ns-resolve 'clojure-mail.message 'read-message)
        messages (get-messages store "INBOX")]
    (if-let [target-msg (first (filter #(= id (:id (read-msg %))) messages))]
      (let [imap-store (:imap-store @(:state channel))
            dest-folder (.getFolder imap-store target-folder)]
        (.open dest-folder javax.mail.Folder/READ_WRITE)
        (.copyMessages (.getFolder target-msg) (into-array [target-msg]) dest-folder)
        (.setFlag target-msg javax.mail.Flags$Flag/DELETED true)
        (.close dest-folder false)
        {:moved true :id id :to target-folder})
      (throw (ex-info (str "Message not found: " id) {:id id})))))

;; ============================================================================
;; Tool definitions
;; ============================================================================

(def ^:private email-capabilities
  #{:email/list :email/read :email/send :email/search
    :email/mark-read :email/delete :email/move})

(def ^:private default-permissions
  #{:email/list :email/read :email/send :email/search :email/mark-read})

(defn- make-tool-defs []
  [{:capability :email/list
    :name "email_list"
    :description "List recent emails from inbox. Returns subject, from, date for each."
    :parameters {:type "object"
                 :properties {:folder {:type "string"
                                       :description "IMAP folder (default INBOX)"}
                              :limit {:type "integer"
                                      :description "Max messages to return (default 20, max 50)"}}}}

   {:capability :email/read
    :name "email_read"
    :description "Read the full content of an email by its ID."
    :parameters {:type "object"
                 :properties {:id {:type "string"
                                   :description "Email message ID"}}
                 :required ["id"]}}

   {:capability :email/send
    :name "email_send"
    :description "Send an email. Rate-limited to prevent spam."
    :parameters {:type "object"
                 :properties {:to {:type "string"
                                   :description "Recipient email address"}
                              :subject {:type "string"
                                        :description "Email subject"}
                              :body {:type "string"
                                     :description "Email body text"}
                              :cc {:type "string"
                                   :description "CC recipient(s)"}
                              :bcc {:type "string"
                                    :description "BCC recipient(s)"}}
                 :required ["to" "subject" "body"]}}

   {:capability :email/search
    :name "email_search"
    :description "Search emails by subject or sender."
    :parameters {:type "object"
                 :properties {:query {:type "string"
                                      :description "Search term (matches subject and from)"}
                              :folder {:type "string"
                                       :description "IMAP folder (default INBOX)"}
                              :limit {:type "integer"
                                      :description "Max results (default 20, max 50)"}}
                 :required ["query"]}}

   {:capability :email/mark-read
    :name "email_mark_read"
    :description "Mark an email as read."
    :parameters {:type "object"
                 :properties {:id {:type "string"
                                   :description "Email message ID"}}
                 :required ["id"]}}

   {:capability :email/delete
    :name "email_delete"
    :description "Delete an email. RESTRICTED - not granted by default."
    :parameters {:type "object"
                 :properties {:id {:type "string"
                                   :description "Email message ID"}}
                 :required ["id"]}}

   {:capability :email/move
    :name "email_move"
    :description "Move an email to a different folder."
    :parameters {:type "object"
                 :properties {:id {:type "string"
                                   :description "Email message ID"}
                              :target_folder {:type "string"
                                              :description "Target IMAP folder name"}}
                 :required ["id" "target_folder"]}}])

;; ============================================================================
;; Tool handlers
;; ============================================================================

(defn- make-handlers [channel-id]
  (let [ch-fn #(channels/get-channel channel-id)]
    {"email_list"      (fn [input _ctx]
                         (let [msgs (list-messages (ch-fn) input)]
                           {:type :success
                            :content (if (seq msgs)
                                       (str "Emails (" (count msgs) "):\n\n"
                                            (str/join "\n\n"
                                              (map-indexed
                                                (fn [i m]
                                                  (str (inc i) ". " (:subject m) "\n"
                                                       "   From: " (:from m) "\n"
                                                       "   Date: " (:date m) "\n"
                                                       "   ID: " (:id m)
                                                       (when (:read? m) " [read]")))
                                                msgs)))
                                       "No emails found.")
                            :metadata {:count (count msgs) :messages msgs}}))

     "email_read"      (fn [input _ctx]
                         (let [msg (read-message (ch-fn) input)]
                           {:type :success
                            :content (str "Subject: " (:subject msg) "\n"
                                         "From: " (:from msg) "\n"
                                         "To: " (:to msg) "\n"
                                         "Date: " (:date msg) "\n\n"
                                         (:body msg))
                            :metadata msg}))

     "email_send"      (fn [input _ctx]
                         (let [result (send-email! (ch-fn) input)]
                           {:type :success
                            :content (str "Email sent to " (:to input)
                                         ". Subject: " (:subject input))
                            :metadata result}))

     "email_search"    (fn [input _ctx]
                         (let [results (search-messages (ch-fn) input)]
                           {:type :success
                            :content (if (seq results)
                                       (str "Search results (" (count results) "):\n\n"
                                            (str/join "\n\n"
                                              (map-indexed
                                                (fn [i m]
                                                  (str (inc i) ". " (:subject m) "\n"
                                                       "   From: " (:from m) "\n"
                                                       "   Date: " (:date m) "\n"
                                                       "   ID: " (:id m)))
                                                results)))
                                       "No emails matching query.")
                            :metadata {:count (count results) :results results}}))

     "email_mark_read" (fn [input _ctx]
                         (let [result (mark-read! (ch-fn) input)]
                           {:type :success
                            :content (str "Marked email " (:id input) " as read.")
                            :metadata result}))

     "email_delete"    (fn [input _ctx]
                         (let [result (delete-email! (ch-fn) input)]
                           {:type :success
                            :content (str "Deleted email " (:id input))
                            :metadata result}))

     "email_move"      (fn [input _ctx]
                         (let [result (move-email! (ch-fn) input)]
                           {:type :success
                            :content (str "Moved email " (:id input) " to " (:target_folder input))
                            :metadata result}))}))

;; ============================================================================
;; Channel constructor
;; ============================================================================

(defn make-email
  "Create an email channel (IMAP + SMTP).

   Config:
     :imap {:host \"...\" :port 993 :user \"...\" :pass \"...\"}
     :smtp {:host \"...\" :port 587 :user \"...\" :pass \"...\"}
     :permissions       - Override default permissions (optional)
     :max-list-results  - Max emails per list/search query (default 50)
     :send-rate-limit   - Max sends per minute (default 10)

   Returns a channel map ready for (channels/connect!)."
  [{:keys [imap smtp permissions max-list-results send-rate-limit] :as config}]
  {:pre [(map? imap) (:host imap) (:user imap) (:pass imap)
         (map? smtp) (:host smtp) (:user smtp) (:pass smtp)]}
  (let [channel-id (keyword (str "email-" (str/replace (:user imap) #"@.*" "")))
        perms (or permissions default-permissions)
        rate-limiter (make-rate-limiter (or send-rate-limit 10))
        state (atom {:connected? false
                     :imap-store nil
                     :send-rate-limiter rate-limiter
                     :on-message nil})]
    (channels/make-channel
     {:id           channel-id
      :type         :email
      :config       (assoc config :max-list-results (or max-list-results 50))
      :capabilities email-capabilities
      :permissions  perms
      :tools        (make-tool-defs)
      :handlers     (make-handlers channel-id)
      :connect!     (fn [channel]
                      ;; Verify IMAP connection eagerly
                      (try
                        (require-mail!)
                        (get-imap-store channel)
                        (catch Exception e
                          (throw (ex-info (str "Failed to connect to IMAP: " (.getMessage e))
                                         {:imap-host (:host imap)}))))
                      (swap! state assoc :connected? true)
                      channel)
      :disconnect!  (fn [channel]
                      (swap! state assoc :connected? false)
                      (when-let [store (:imap-store @state)]
                        (try (.close store) (catch Exception _)))
                      (swap! state assoc :imap-store nil))
      :state        state})))
