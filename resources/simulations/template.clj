;; == Simulation Template ==
;;
;; Copy this template to create a new simulation.
;; Simulations are Clojure code that runs in an SCI sandbox with:
;;
;; Available namespaces:
;;   dh/*           — Datahike (simulation's own dedicated DB)
;;                    dh/q, dh/pull, dh/db, dh/transact!, dh/entity, dh/datoms
;;   intake.hn      — hn/search, hn/top
;;   intake.web     — web/fetch (HTML→text)
;;   intake.github  — github/search-repos, github/releases, github/trending,
;;                    github/user, github/contributors, github/search-code,
;;                    github/org-members, github/issues, github/repo-details
;;   intake.reddit  — reddit/search, reddit/top
;;   intake.zulip   — zulip/streams, zulip/messages, zulip/search, zulip/topics
;;   intake.lobsters — lobsters/hottest
;;   intake.bluesky — bluesky/search
;;   intake.yt      — yt/transcript, yt/transcript-summary (auto-summarize via LLM)
;;   intake.tw      — tw/lookup
;;   intake.mail    — mail/inbox, mail/search, mail/read, mail/sync!
;;   intake.devto   — devto/top (top articles, filter by :tag :time-range :count)
;;   intake.mastodon — mastodon/trending (trending posts/links, :instance :type :count)
;;   intake.sec     — sec/search-companies, sec/company-facts (XBRL financials),
;;                    sec/filings (10-K/10-Q/8-K), sec/insider-trades
;;   intake.uk      — uk/search-companies, uk/company, uk/officers, uk/filings, uk/psc
;;                    (requires COMPANIES_HOUSE_API_KEY env var)
;;   intake.stock   — stock/quote, stock/profile, stock/earnings, stock/news,
;;                    stock/insiders, stock/peers, stock/financials
;;                    (requires FINNHUB_API_KEY env var)
;;   intake.rss     — rss/discover (find feeds from URL), rss/feed (fetch+parse feed)
;;   llm            — llm/call(prompt, content, opts), llm/summarize(text, opts)
;;
;; Java classes: java.util.Date, java.util.UUID, java.time.Instant, System, Math
;;
;; IMPORTANT: All integer values stored in Datahike must be (long ...) coerced.
;; GitHub API returns Java Integer, not Long.
;;
;; The simulation's entry point is the last expression evaluated.
;; Return a map with summary data from the sweep.

;; ============================================================================
;; 1. Define Schema (first time only)
;; ============================================================================

(def schema
  [{:db/ident :entity/name   :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :entity/type   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/notes  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :signal/url    :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :signal/type   :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :signal/title  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :signal/timestamp :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])

(when-not (dh/q '[:find ?e . :where [?e :db/ident :entity/name]] (dh/db))
  (dh/transact! schema))

;; ============================================================================
;; 2. Require intake namespaces
;; ============================================================================

(require '[intake.github :as github])
(require '[intake.web :as web])
(require '[intake.zulip :as zulip])
(require '[intake.hn :as hn])
(require '[llm])

;; ============================================================================
;; 3. Define sweep functions
;; ============================================================================

(def now (java.util.Date.))

(defn sweep! []
  ;; TODO: Implement your data collection here
  ;; Example: (github/repo-details "org/repo")
  ;; Example: (web/fetch "https://..." {:max-chars 5000})
  ;; Example: (zulip/search "topic" {:count 10})
  ;; Example: (hn/search "keyword" {:days-back 7})
  ;;
  ;; Store results: (dh/transact! [{:entity/name "..." :entity/type :...}])
  ;; Query results: (dh/q '[:find ...] (dh/db))
  ;;
  ;; Use LLM for analysis:
  ;; (llm/call "Analyze this:" content {})
  ;; (llm/summarize text {})
  {:status :ok :timestamp now})

;; ============================================================================
;; Entry Point
;; ============================================================================

(sweep!)
