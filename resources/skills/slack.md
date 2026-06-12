---
name: slack
description: Post and read messages from Slack channels
provides: [:chat-bridge, :slack]
requires_tools: [clojure_eval]
requires_env: [SLACK_TOKEN]
vetted: true
vetted_at: 2026-05-26
vetted_by: ch_weil
source: dvergr
---

# Slack Integration

Post and read messages from Slack channels using the Slack Web API.

## Setup

Set your Slack Bot token:
```clojure
(env/set "SLACK_TOKEN" "xoxb-your-bot-token")
```

Get a token: https://api.slack.com/apps → Create App → Bot Token Scopes: `chat:write`, `channels:read`, `channels:history`

## Usage

```clojure
(require '[http] '[env])

(def slack-token (env/get "SLACK_TOKEN"))

(defn slack-api [method params]
  (let [resp (http/post (str "https://slack.com/api/" method)
               {:headers {"Authorization" (str "Bearer " slack-token)}
                :json params})]
    (if (:ok (:body resp))
      (:body resp)
      (throw (ex-info (str "Slack error: " (:error (:body resp))) (:body resp))))))

;; Post a message
(slack-api "chat.postMessage"
  {:channel "#general" :text "Hello from dvergr!"})

;; Read recent messages
(slack-api "conversations.history"
  {:channel "C01234567" :limit 10})

;; List channels
(slack-api "conversations.list" {:limit 20})

;; React to a message
(slack-api "reactions.add"
  {:channel "C01234567" :timestamp "1234567890.123456" :name "thumbsup"})
```
