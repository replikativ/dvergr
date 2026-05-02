(ns dvergr.intake.github-metrics
  "GitHub metrics intake for replikativ org repos.

   Fetches stars, forks, open issues, and (with auth) traffic data.
   Auth: set GITHUB_TOKEN env var for traffic endpoints (requires repo owner/collaborator).

   Tool registered as 'github_metrics'."
  (:require [dvergr.intake.core :as intake]
            [dvergr.config :as config]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private gh-api "https://api.github.com")
(def ^:private default-org "replikativ")

(defn- auth-headers []
  (when-let [token (config/github-token)]
    {"Authorization" (str "Bearer " token)}))

(defn- fetch-gh
  "GET a GitHub API endpoint. Injects auth header if GITHUB_TOKEN is set."
  [path & {:keys [query-params]}]
  (intake/fetch-json (str gh-api path)
                     :headers (merge {"X-GitHub-Api-Version" "2022-11-28"}
                                     (auth-headers))
                     :query-params query-params))

;; ============================================================================
;; Repo Listing
;; ============================================================================

(defn list-org-repos
  "List all repositories for an org. Returns vec of repo maps.
   Paginates up to 10 pages (300 repos)."
  [org]
  (loop [page 1 acc []]
    (let [data (fetch-gh (str "/orgs/" org "/repos")
                         :query-params {:per_page 30 :page page :sort "updated"})]
      (if (or (:error data) (empty? data))
        acc
        (let [repos (into acc
                          (map (fn [r]
                                 {:name        (:name r)
                                  :full-name   (:full_name r)
                                  :description (:description r)
                                  :stars       (:stargazers_count r)
                                  :forks       (:forks_count r)
                                  :watchers    (:watchers_count r)
                                  :open-issues (:open_issues_count r)
                                  :language    (:language r)
                                  :private?    (:private r)
                                  :updated-at  (:updated_at r)
                                  :url         (:html_url r)}))
                          data)]
          (if (< (count data) 30)
            repos
            (recur (inc page) repos)))))))

;; ============================================================================
;; Traffic (requires auth + owner access)
;; ============================================================================

(defn fetch-repo-traffic
  "Fetch traffic data for a specific repo (views + clones, last 14 days).
   Requires GITHUB_TOKEN with repo scope and owner/collaborator access."
  [org repo]
  (let [views  (fetch-gh (str "/repos/" org "/" repo "/traffic/views"))
        clones (fetch-gh (str "/repos/" org "/" repo "/traffic/clones"))]
    (cond-> {:repo (str org "/" repo)}
      (not (:error views))
      (assoc :views-14d       (:count views)
             :unique-views-14d (:uniques views))
      (not (:error clones))
      (assoc :clones-14d        (:count clones)
             :unique-clones-14d (:uniques clones))
      (:error views)
      (assoc :traffic-error (:error views)))))

(defn fetch-referrers
  "Top referrer sources for a repo (last 14 days). Requires auth."
  [org repo]
  (let [data (fetch-gh (str "/repos/" org "/" repo "/traffic/popular/referrers"))]
    (if (:error data)
      nil
      (mapv (fn [r] {:referrer (:referrer r)
                     :count    (:count r)
                     :uniques  (:uniques r)})
            data))))

;; ============================================================================
;; High-level queries
;; ============================================================================

(defn org-metrics
  "Get summary metrics for all (or specified) repos in an org.
   Options:
     :repos     — vec of repo names to include (default: all)
     :traffic?  — fetch traffic data (requires auth, default: true if token set)
     :org       — org name (default: \"replikativ\")"
  [& {:keys [repos org traffic?]
      :or {org default-org}}]
  (let [all-repos (list-org-repos org)
        filtered  (if repos
                    (filter #((set repos) (:name %)) all-repos)
                    all-repos)
        has-token? (boolean (System/getenv "GITHUB_TOKEN"))
        fetch-traffic? (if (nil? traffic?) has-token? traffic?)]
    {:org          org
     :repo-count   (count filtered)
     :total-stars  (reduce + 0 (map :stars filtered))
     :total-forks  (reduce + 0 (map :forks filtered))
     :repos        (if fetch-traffic?
                     (mapv (fn [r]
                             (let [traffic (fetch-repo-traffic org (:name r))]
                               (merge r (dissoc traffic :repo))))
                           filtered)
                     filtered)}))

(defn format-metrics-report
  "Format org metrics as a human-readable string."
  [{:keys [org repo-count total-stars total-forks repos]}]
  (str "## GitHub Metrics — " org "\n\n"
       "Total: " total-stars " stars, " total-forks " forks across "
       repo-count " repos\n\n"
       (str/join "\n"
         (for [r (sort-by #(- (:stars %)) repos)
               :when (pos? (:stars r))]
           (str "**" (:name r) "** — "
                (:stars r) "★  "
                (:forks r) " forks"
                (when (:views-14d r)
                  (str "  |  " (:views-14d r) " views, "
                       (:unique-views-14d r) " unique (14d)"))
                (when (:description r)
                  (str "\n  " (:description r))))))))

;; ============================================================================
;; Tool registration
;; ============================================================================

(tools/register!
  {:name "github_metrics"
   :description "Fetch GitHub metrics for the replikativ org (stars, forks, traffic).
Retrieves repository statistics. Traffic data (views, clones) requires GITHUB_TOKEN env var
with repo scope. Without token, returns public stats only.

Examples:
- All replikativ repos: {}
- Specific repos: {:repos [\"stratum\" \"datahike\"]}
- Specific org: {:org \"replikativ\" :repos [\"stratum\"]}"
   :parameters {:type "object"
                :properties
                {:org   {:type "string"
                         :description "GitHub org (default: replikativ)"}
                 :repos {:type "array" :items {:type "string"}
                         :description "Specific repo names to include (default: all)"}
                 :traffic? {:type "boolean"
                            :description "Include traffic data (default: true if token set)"}}
                :required []}
   :handler (fn [{:keys [org repos traffic?]}]
              (let [metrics (org-metrics
                              :org (or org default-org)
                              :repos repos
                              :traffic? traffic?)]
                {:result (format-metrics-report metrics)
                 :data   metrics}))})
