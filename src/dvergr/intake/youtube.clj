(ns dvergr.intake.youtube
  "YouTube transcript fetcher using the InnerTube API.

   Strategy (ported from github.com/jdepoix/youtube-transcript-api):
   1. GET video page → extract INNERTUBE_API_KEY
   2. POST /youtubei/v1/player with ANDROID client context → avoids bot detection
   3. Extract captionTracks from player response
   4. GET transcript XML from track URL
   5. Strip XML tags → plain text

   No Python, no API key, no yt-dlp required.
   ANDROID client context is the key trick — YouTube's bot detection mostly
   targets browser-impersonation clients, not the official Android app API."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [dvergr.search :as search]
            [hato.client :as http]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ── HTTP helpers ─────────────────────────────────────────────────────────────

(def ^:private browser-ua
  "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0")

(def ^:private android-context
  {"context" {"client" {"clientName"    "ANDROID"
                         "clientVersion" "20.10.38"}}})

(defn- get-text [url & {:keys [headers] :or {headers {}}}]
  (try
    (let [resp (http/get url
                         {:headers          (merge {"User-Agent"      browser-ua
                                                    "Accept-Language" "en-US"}
                                                   headers)
                          :throw-exceptions? false
                          :as               :string
                          :connect-timeout  15000})]
      (when (<= 200 (:status resp) 299)
        (:body resp)))
    (catch Exception _ nil)))

(defn- post-json [url body]
  (try
    (let [resp (http/post url
                          {:headers           {"User-Agent"      browser-ua
                                               "Accept-Language" "en-US"
                                               "Content-Type"    "application/json"}
                           :body              (json/write-value-as-string body)
                           :throw-exceptions? false
                           :as                :string
                           :connect-timeout   15000})]
      (when (<= 200 (:status resp) 299)
        (json/read-value (:body resp) json/keyword-keys-object-mapper)))
    (catch Exception _ nil)))

;; ── Video ID extraction ───────────────────────────────────────────────────────

(defn extract-video-id
  "Parse a video ID from any YouTube URL format, or return as-is for bare IDs."
  [url-or-id]
  (or (some-> (re-find #"[?&]v=([A-Za-z0-9_-]+)" url-or-id) second)
      (some-> (re-find #"youtu\.be/([A-Za-z0-9_-]+)" url-or-id) second)
      (some-> (re-find #"youtube\.com/shorts/([A-Za-z0-9_-]+)" url-or-id) second)
      (some-> (re-find #"youtube\.com/embed/([A-Za-z0-9_-]+)" url-or-id) second)
      ;; Bare 11-char ID
      (when (re-matches #"[A-Za-z0-9_-]{11}" url-or-id) url-or-id)))

;; ── InnerTube player fetch ────────────────────────────────────────────────────

(defn- fetch-innertube-key
  "Extract INNERTUBE_API_KEY from the video page HTML. Returns nil if not found."
  [video-id]
  (when-let [html (get-text (str "https://www.youtube.com/watch?v=" video-id))]
    (some-> (re-find #"\"INNERTUBE_API_KEY\":\s*\"([A-Za-z0-9_-]+)\"" html) second)))

(defn- fetch-player-data
  "POST to InnerTube /player endpoint using the ANDROID client.
   Returns parsed JSON player response or nil."
  [video-id innertube-key]
  (let [url  (cond-> "https://www.youtube.com/youtubei/v1/player"
               innertube-key (str "?key=" innertube-key))
        body (assoc android-context "videoId" video-id)]
    (post-json url body)))

(defn- extract-playability
  "Check playability status — returns :ok, :bot-blocked, :age-restricted, or :error."
  [player-data]
  (let [status (get-in player-data [:playabilityStatus :status])
        reason (get-in player-data [:playabilityStatus :reason] "")]
    (cond
      (= "OK" status)                             :ok
      (str/includes? reason "confirm you're not") :bot-blocked
      (str/includes? reason "inappropriate")      :age-restricted
      :else                                        :error)))

(defn- extract-caption-tracks
  "Extract captionTracks from player response."
  [player-data]
  (get-in player-data [:captions :playerCaptionsTracklistRenderer :captionTracks]))

(defn- extract-title
  "Best-effort title from player response."
  [player-data]
  (or (get-in player-data [:videoDetails :title])
      "Unknown title"))

;; ── Transcript XML fetch & parse ──────────────────────────────────────────────

(defn- unescape-html
  "Decode the HTML entities that appear in YouTube transcript XML."
  [s]
  (-> s
      (str/replace #"&amp;"  "&")
      (str/replace #"&lt;"   "<")
      (str/replace #"&gt;"   ">")
      (str/replace #"&quot;" "\"")
      (str/replace #"&#39;"  "'")
      (str/replace #"&#x27;" "'")
      (str/replace #"&apos;" "'")))

(defn- parse-transcript-xml
  "Extract text from YouTube transcript XML.
   Handles two formats:
   - Format 3 (InnerTube API): <p t='...' d='...'>text</p>
   - Legacy timedtext:         <text start='...' dur='...'>text</text>"
  [xml-str]
  ;; Try <p> tags first (InnerTube format 3), then fall back to <text> tags
  (let [segments (or (seq (re-seq #"<p[^>]+>([\s\S]*?)</p>" xml-str))
                     (seq (re-seq #"<text[^>]*>([\s\S]*?)</text>" xml-str)))]
    (->> segments
         (map second)
         (map #(str/replace % #"<[^>]+>" ""))  ; strip inline tags like <s>
         (map unescape-html)
         (map str/trim)
         (remove str/blank?)
         (str/join " "))))

(defn- fetch-transcript-text
  "Fetch and parse the transcript XML at track-url."
  [track-url]
  (when-let [xml (get-text track-url)]
    (let [text (parse-transcript-xml xml)]
      (when-not (str/blank? text) text))))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn get-transcript
  "Fetch YouTube title + transcript for a video URL or ID.
   Returns {:video-id :title :language :transcript} or {:error ...}."
  [url-or-id]
  (let [video-id (extract-video-id url-or-id)]
    (if-not video-id
      {:error (str "Could not extract YouTube video ID from: " url-or-id)}
      (let [innertube-key (fetch-innertube-key video-id)
            player-data   (fetch-player-data video-id innertube-key)]
        (if-not player-data
          {:error (str "Could not fetch InnerTube player data for: " video-id)}
          (let [playability (extract-playability player-data)]
            (case playability
              :bot-blocked   {:error "YouTube is blocking requests from this IP. Try with a VPN/proxy."}
              :age-restricted {:error "Age-restricted video — requires authentication."}
              :error         {:error (str "YouTube returned non-OK playability: "
                                          (get-in player-data [:playabilityStatus :status]))}
              :ok
              (let [title  (extract-title player-data)
                    tracks (extract-caption-tracks player-data)]
                (if-not (seq tracks)
                  {:video-id video-id :title title
                   :error "No captions/transcript available for this video"}
                  ;; Prefer English; fall back to first track
                  (let [track      (or (first (filter #(= "en" (:languageCode %)) tracks))
                                       (first tracks))
                        transcript (fetch-transcript-text (:baseUrl track))]
                    (if-not transcript
                      {:video-id video-id :title title
                       :error "Fetched caption track but got empty transcript"}
                      {:video-id  video-id
                       :title     title
                       :language  (:languageCode track)
                       :transcript transcript})))))))))))

;; ── Tool registration ─────────────────────────────────────────────────────────

(tools/register!
  {:name        "youtube_transcript"
   :description "Fetch the transcript/captions of a YouTube video. No API key needed. Uses the YouTube InnerTube API (ANDROID client). Accepts full URL or bare video ID. Returns title + full transcript text."
   :parameters  {:type       "object"
                 :properties {:url {:type        "string"
                                    :description "YouTube URL (youtube.com/watch?v=... or youtu.be/...) or bare 11-char video ID"}}
                 :required   ["url"]}
   :execute     (fn [{:keys [url]} _ctx]
                  (let [result (get-transcript url)]
                    (if (:error result)
                      (intake/error-response
                       (if (:video-id result)
                         (str (:error result) " [video: " (:video-id result) "]")
                         (:error result)))
                      (let [transcript (:transcript result)
                            title      (:title result)
                            vid        (:video-id result)
                            _          (try
                                         ;; Save raw transcript to disk
                                         (let [dir (io/file "data/transcripts")]
                                           (.mkdirs dir)
                                           (spit (io/file dir (str vid ".txt")) transcript))
                                         ;; Index in fulltext search
                                         (when (search/initialized?)
                                           (search/index-document!
                                             {:id        (str "youtube/" vid)
                                              :source    "youtube"
                                              :title     title
                                              :content   transcript
                                              :url       (str "https://youtube.com/watch?v=" vid)
                                              :domain    "youtube.com"
                                              :timestamp (System/currentTimeMillis)
                                              :metadata  {:language (:language result)}}))
                                         (catch Exception _ nil))
                            t-text     (if (> (count transcript) 15000)
                                         (str (subs transcript 0 15000) "\n\n[transcript truncated]")
                                         transcript)
                            content    (str "**" title "**\n"
                                            "Video: https://youtube.com/watch?v=" vid "\n"
                                            "Language: " (:language result) "\n\n"
                                            t-text)]
                        (intake/success-response
                         content :youtube 1
                         [{:title   title
                           :url     (str "https://youtube.com/watch?v=" (:video-id result))
                           :summary (subs transcript 0 (min 300 (count transcript)))}])))))})
