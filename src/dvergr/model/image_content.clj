(ns dvergr.model.image-content
  "Inline image translation for subscription transports. Never fetches URLs or
   reads filesystem paths; callers supply the image bytes explicitly."
  (:import [java.util Base64]))

(defn parts
  "Validate ordered text/image_url parts. Images must be bounded inline data
   URLs. Returns neutral text or MIME/base64 parts for wire encoders."
  [content]
  (when-not (and (vector? content) (<= (count content) 64))
    (throw (ex-info "Expected at most 64 image/text parts" {:type ::invalid})))
  (when (> (count (filter #(= "image_url" (:type %)) content)) 16)
    (throw (ex-info "At most 16 inline images per message" {:type ::invalid})))
  (when (> (reduce + 0 (map #(let [url (get-in % [:image_url :url])]
                               (if (string? url) (count url) 0)) content)) 20971520)
    (throw (ex-info "Inline images exceed the 20 MiB message limit" {:type ::invalid})))
  (mapv
   (fn [{:keys [type text image_url]}]
     (case type
       "text" (if (string? text) {:text text}
                  (throw (ex-info "Image message text must be a string" {:type ::invalid})))
       "image_url"
       (let [url (:url image_url)
             [_ mime encoded] (when (and (string? url) (<= (count url) 20971520))
                                (re-matches #"data:(image/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/=]+)" url))]
         (when-not mime
           (throw (ex-info "Images require a supported inline base64 data URL (max 20 MiB)"
                           {:type ::invalid})))
         (let [bytes (try (.decode (Base64/getDecoder) ^String encoded)
                          (catch IllegalArgumentException _
                            (throw (ex-info "Invalid image base64" {:type ::invalid}))))]
           (when-not (= encoded (.encodeToString (Base64/getEncoder) bytes))
             (throw (ex-info "Noncanonical image base64" {:type ::invalid}))))
         {:mime mime :data encoded :url url
          :detail (let [detail (or (:detail image_url) "auto")]
                    (when-not (contains? #{"auto" "low" "high" "original"} detail)
                      (throw (ex-info "Invalid image detail" {:type ::invalid})))
                    detail)})
       (throw (ex-info "Unsupported subscription content part" {:type ::invalid}))))
   content))

(defn responses [content]
  (mapv (fn [{:keys [text url detail]}]
          (if url {:type "input_image" :image_url url :detail detail}
              {:type "input_text" :text text}))
        (parts content)))

(defn anthropic [content]
  (mapv (fn [{:keys [text mime data]}]
          (if mime {:type "image" :source {:type "base64" :media_type mime :data data}}
              {:type "text" :text text}))
        (parts content)))
