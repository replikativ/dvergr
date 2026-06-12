---
name: notion
description: Create and query Notion pages and databases
provides: [:documentation, :notion]
requires_tools: [clojure_eval]
requires_env: [NOTION_API_KEY]
vetted: true
vetted_at: 2026-05-26
vetted_by: ch_weil
source: dvergr
---

# Notion Integration

Create, read, and search Notion pages and databases.

## Setup

```clojure
(env/set "NOTION_API_KEY" "ntn_your-api-key")
```

Get a key: https://notion.so/my-integrations → New Integration.
Share target pages with the integration.

## Usage

```clojure
(require '[http] '[env])

(def notion-key (env/get "NOTION_API_KEY"))

(defn notion-api [method path & [body]]
  (let [resp (http/request
               {:url (str "https://api.notion.com/v1" path)
                :method method
                :headers {"Authorization" (str "Bearer " notion-key)
                          "Notion-Version" "2022-06-28"
                          "Content-Type" "application/json"}
                :json body})]
    (:body resp)))

;; Search pages
(notion-api :post "/search"
  {:query "meeting notes" :page_size 5})

;; Get a page
(notion-api :get "/pages/PAGE_ID")

;; Create a page in a database
(notion-api :post "/pages"
  {:parent {:database_id "DB_ID"}
   :properties {:Name {:title [{:text {:content "New Page"}}]}
                :Status {:select {:name "In Progress"}}}})

;; Query a database
(notion-api :post "/databases/DB_ID/query"
  {:filter {:property "Status"
            :select {:equals "Active"}}
   :page_size 10})

;; Append content to a page
(notion-api :patch "/blocks/PAGE_ID/children"
  {:children [{:object "block"
               :type "paragraph"
               :paragraph {:rich_text [{:text {:content "Added by dvergr agent"}}]}}]})
```
