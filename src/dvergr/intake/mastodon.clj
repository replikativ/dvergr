(ns dvergr.intake.mastodon
  "Mastodon intake via public API (no auth required)."
  (:require [dvergr.intake.core :as intake]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private default-instance "fosstodon.org")

(defn- strip-html [s]
  (when s
    (-> s
        (str/replace #"<br\s*/?>" "\n")
        (str/replace #"<[^>]+>" "")
        (str/replace "&amp;" "&")
        (str/replace "&lt;" "<")
        (str/replace "&gt;" ">")
        (str/replace "&quot;" "\"")
        (str/replace "&#39;" "'")
        str/trim)))

(defn- parse-status [status]
  {:title (let [text (strip-html (:content status))]
            (if (> (count text) 120)
              (str (subs text 0 120) "...")
              text))
   :url (:url status)
   :score (+ (or (:favourites_count status) 0)
             (or (:reblogs_count status) 0))
   :comments (:replies_count status)
   :source :mastodon
   :summary (strip-html (:content status))})

(defn- parse-link [link]
  {:title (:title link)
   :url (:url link)
   :score (or (:history link)
              0)
   :source :mastodon
   :summary (:description link)})

(defn fetch-trending
  "Fetch trending statuses or links from a Mastodon instance."
  [& {:keys [instance type count]
      :or {instance default-instance type "statuses" count 20}}]
  (let [url (str "https://" instance "/api/v1/trends/" type)
        data (intake/fetch-json url
                                :query-params {:limit (min count 40)})]
    (if (:error data)
      data
      (->> data
           (take count)
           (mapv (if (= type "links") parse-link parse-status))))))

(tools/register!
 {:name "mastodon_trending"
  :description "Get trending posts or links from Mastodon instances. Good for open-source and tech community discourse. Default instance: fosstodon.org (FOSS-focused)."
  :parameters {:type "object"
               :properties {:instance {:type "string"
                                       :description "Mastodon instance domain (default fosstodon.org). Others: mastodon.social, hachyderm.io"}
                            :type {:type "string"
                                   :description "What to fetch: statuses (posts) or links (shared URLs)"
                                   :enum ["statuses" "links"]}
                            :count {:type "integer"
                                    :description "Number of items (default 20)"}}
               :required []}
  :execute (fn [{:keys [instance type count]} _ctx]
             (let [inst (or instance default-instance)
                   t (or type "statuses")
                   items (fetch-trending :instance inst :type t :count (or count 20))]
               (if (:error items)
                 (intake/error-response (:error items))
                 (intake/success-response
                  (intake/format-items
                   (str "Mastodon " t " (" inst ")")
                   items)
                  :mastodon (clojure.core/count items) items))))})
