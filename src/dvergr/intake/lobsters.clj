(ns dvergr.intake.lobsters
  "Lobste.rs intake via JSON API (no auth required)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]))

(def ^:private base-url "https://lobste.rs")

(defn- parse-story [story]
  {:title (:title story)
   :url (or (:url story) (:comments_url story))
   :score (:score story)
   :comments (:comment_count story)
   :tags (:tags story)
   :source :lobsters
   :submitter (:submitter_user story)})

(defn fetch-hottest
  "Fetch hottest stories, optionally filtered by tag."
  [& {:keys [tag count]
      :or {count 20}}]
  (let [url (if tag
              (str base-url "/t/" tag ".json")
              (str base-url "/hottest.json"))
        data (intake/fetch-json url)]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv parse-story)))))

(tools/register!
 {:name "lobsters_top"
  :description "Get top stories from Lobste.rs. Tech-focused community with high signal-to-noise ratio. Supports tag filtering (e.g. 'ai', 'clojure', 'rust', 'programming')."
  :parameters {:type "object"
               :properties {:tag {:type "string"
                                  :description "Filter by tag (e.g. 'ai', 'clojure', 'rust', 'programming')"}
                            :count {:type "integer"
                                    :description "Number of stories to return (default 20)"}}
               :required []}
  :execute (fn [{:keys [tag count]} _ctx]
             (let [stories (fetch-hottest :tag tag :count (or count 20))]
               (if (:error stories)
                 (intake/error-response (:error stories))
                 (intake/success-response
                  (intake/format-items
                   (str "Lobste.rs" (when tag (str " [" tag "]")))
                   stories)
                  :lobsters (clojure.core/count stories) stories))))})
