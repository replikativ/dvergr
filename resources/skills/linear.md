---
name: linear
description: Manage Linear issues, projects, and cycles
provides: [:project-management, :linear]
requires_tools: [clojure_eval]
requires_env: [LINEAR_API_KEY]
vetted: true
vetted_at: 2026-05-26
vetted_by: ch_weil
source: dvergr
---

# Linear Integration

Create and manage issues, projects, and cycles via Linear's GraphQL API.

## Setup

```clojure
(env/set "LINEAR_API_KEY" "lin_api_your-key")
```

Get a key: Linear Settings → API → Personal API Keys

## Usage

```clojure
(require '[http] '[env])

(def linear-key (env/get "LINEAR_API_KEY"))

(defn linear-gql [query & [variables]]
  (let [resp (http/post "https://api.linear.app/graphql"
               {:headers {"Authorization" linear-key
                          "Content-Type" "application/json"}
                :json {:query query :variables (or variables {})}})]
    (get-in (:body resp) [:data])))

;; List my issues
(linear-gql "{ viewer { assignedIssues(first: 10) { nodes { title state { name } priority } } } }")

;; Create an issue
(linear-gql
  "mutation($title: String!, $teamId: String!) {
     issueCreate(input: {title: $title, teamId: $teamId}) {
       issue { id title url }
     }
   }"
  {:title "Investigate Snowflake integration"
   :teamId "TEAM_ID"})

;; Search issues
(linear-gql
  "query($term: String!) {
     issueSearch(term: $term, first: 5) {
       nodes { title state { name } assignee { name } }
     }
   }"
  {:term "authentication"})

;; List teams
(linear-gql "{ teams { nodes { id name key } } }")
```
