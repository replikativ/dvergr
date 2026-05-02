---
name: discord
description: Send and read Discord messages via bot API
requires_tools: [clojure_eval]
requires_env: [DISCORD_BOT_TOKEN]
---

# Discord Integration

Send messages, read channels, and manage Discord servers via the Bot API.

## Setup

```clojure
(env/set "DISCORD_BOT_TOKEN" "your-bot-token")
```

Create a bot: https://discord.com/developers/applications → New Application → Bot → Token

## Usage

```clojure
(require '[http] '[env])

(def discord-token (env/get "DISCORD_BOT_TOKEN"))

(defn discord-api [method path & [body]]
  (let [resp (http/request
               {:url (str "https://discord.com/api/v10" path)
                :method method
                :headers {"Authorization" (str "Bot " discord-token)
                          "Content-Type" "application/json"}
                :json body})]
    (:body resp)))

;; Send a message
(discord-api :post "/channels/CHANNEL_ID/messages"
  {:content "Hello from dvergr!"})

;; Read recent messages
(discord-api :get "/channels/CHANNEL_ID/messages?limit=10")

;; List guild channels
(discord-api :get "/guilds/GUILD_ID/channels")

;; Create a thread
(discord-api :post "/channels/CHANNEL_ID/threads"
  {:name "Analysis Thread" :auto_archive_duration 60 :type 11})
```
