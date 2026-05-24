(ns dvergr.cli.streaming
  "Streaming-aware run-turn-fn for dvergr-cli.

   Wraps `dvergr.chat.agent/run-agent-turn!` with an `:on-text` callback
   that posts `:partial/token` messages onto the supplied bus. Subscribers
   (typically the TUI's render loop) see each chunk as it arrives — the
   bus's default fixed-buffer-256 policy on the `:partial` namespace
   keeps every token; a UI that only wants 'current accumulated state'
   overrides its subscription with sliding-buffer 1.

   Usage:

     (llm/llm-agent
       {:id          :coder
        :spec        {:provider :fireworks :model \"...\"}
        :run-turn-fn (cli-streaming/make-run-turn-fn
                       {:bus            (:bus room)
                        :assistant-id   :coder
                        :recipient-id   :you})})

   When the LLM emits text, a message is posted with:
     {:type     :partial/token
      :from     <assistant-id>
      :to       <recipient-id>      ; so [:to :you] subscriptions wake too
      :payload  <chunk-string>}"
  (:require [dvergr.bus :as bus]
            [dvergr.chat.agent :as ca]))

(defn make-run-turn-fn
  "Build a run-turn-fn that streams partial tokens onto `bus`.

   :bus           — the dvergr.bus.Bus to post onto
   :assistant-id  — sender id for the :partial/token messages
   :recipient-id  — addressee (so a [:to recipient-id] subscriber also gets it)
   :delegate      — underlying run-turn-fn (default: ca/run-agent-turn!)"
  [{:keys [bus assistant-id recipient-id delegate]
    :or   {delegate ca/run-agent-turn!}}]
  (fn run-turn [chat-ctx opts]
    (let [on-text (fn [chunk]
                    (when (and (string? chunk) (not= chunk ""))
                      (bus/post! bus {:type    :partial/token
                                      :from    assistant-id
                                      :to      recipient-id
                                      :payload chunk})))]
      (delegate chat-ctx (assoc opts :on-text on-text)))))
