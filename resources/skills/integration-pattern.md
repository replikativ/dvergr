---
name: integration-pattern
description: Template for building REST API integrations in SCI
provides: [:integration, :rest-api]
requires_tools: [clojure_eval]
vetted: true
vetted_at: 2026-05-26
vetted_by: ch_weil
source: dvergr
---

# Building API Integrations

You can build integrations with any REST API using the `babashka.http-client` and `env` namespaces.

## Pattern

```clojure
(require '[babashka.http-client :as http] '[cheshire.core :as json] '[env] '[clojure.string :as str])

;; 1. Get API credentials from user config / env
(def token (env/get "SERVICE_TOKEN"))
(when-not token
  (throw (ex-info "SERVICE_TOKEN not configured. Set it with (env/set \"SERVICE_TOKEN\" \"your-token\")" {})))

;; 2. Define API helper
(defn api-call [method path & [opts]]
  (let [resp (http/request
               (merge {:url (str "https://api.service.com" path)
                       :method method
                       :headers {"Authorization" (str "Bearer " token)
                                 "Content-Type" "application/json"}}
                      opts))]
    (if (< (:status resp) 400)
      (json/parse-string (:body resp) true)
      (throw (ex-info (str "API error " (:status resp)) resp)))))

;; 3. Define operations
(defn list-items [] (api-call :get "/items"))
(defn create-item [data] (api-call :post "/items" {:json data}))
(defn get-item [id] (api-call :get (str "/items/" id)))
```

## Available Facilities

- `(http/get url opts?)` — GET request; `:body` is the raw string, parse it with `(json/parse-string body true)`
- `(http/post url opts?)` — POST with `:json` or `:body`
- `(http/put url opts?)`, `(http/patch url opts?)`, `(http/delete url opts?)`
- `(env/get "KEY")` — read a key granted to you (not the host environment; a configured secret comes back as a placeholder that HTTP substitutes at its bound API)
- `(env/set "KEY" "value")` — set a key for this sandbox session
- `(env/keys)` — list configured keys
- `(llm/summarize text)` — summarize long API responses
- `(llm/call prompt text)` — extract/transform data via LLM
