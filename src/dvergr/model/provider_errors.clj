(ns dvergr.model.provider-errors
  "Safe diagnostic text for provider failures."
  (:require [clojure.string :as str]))

(defn diagnostic-url [url]
  ;; Reconstruct from components: credentials, query and fragment never escape.
  (try
    (let [uri (java.net.URI. url)]
      (if (and (.getScheme uri) (.getHost uri))
        (str (.getScheme uri) "://" (.getHost uri)
             (when (not= -1 (.getPort uri)) (str ":" (.getPort uri)))
             (.getRawPath uri))
        "[invalid URL]"))
    (catch Exception _ "[invalid URL]")))

(defn sanitize-message
  "Redact provider credentials and bound diagnostic text."
  ([message] (sanitize-message message []))
  ([message secrets] (sanitize-message message secrets {}))
  ([message secrets {:keys [max-length truncate-marker?] :or {max-length 2048 truncate-marker? true}}]
   (let [redacted (reduce (fn [s secret]
                            (if (str/blank? secret) s (str/replace s secret "[REDACTED]")))
                          (str message) secrets)
         redacted (-> redacted
                      (str/replace #"(?i)[a-z][a-z0-9+.-]*://[^\s\"<>]+" diagnostic-url)
                      (str/replace #"(?i)(authorization[\"']?\s*(?::|=|\s)\s*[\"']?)[^\r\n\"'}]+" "$1[REDACTED]")
                      (str/replace #"(?i)\bbearer\s+[^\s\"',;}]+" "Bearer [REDACTED]")
                      (str/replace #"(?i)((?:[a-z0-9_-]*api[_ -]?key|access[_-]?token|refresh[_-]?token|token|secret)[\"']?\s*[:=]\s*[\"']?)[^\r\n\"',;}]+" "$1[REDACTED]")
                      (str/replace #"(?i)([?&][^=&#\s]*(?:key|token|secret|signature|auth)[^=&#\s]*=)[^&#\s\"']*" "$1[REDACTED]")
                      (str/replace #"(?i)\b(?:sk|gsk|fw)[-_][A-Za-z0-9_-]+" "[REDACTED]"))]
     (if (> (count redacted) max-length)
       (if truncate-marker?
         (str (subs redacted 0 (- max-length 14)) "...[truncated]")
         (subs redacted 0 max-length))
       redacted))))

