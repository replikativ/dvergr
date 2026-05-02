(ns dvergr.agent
  "Public API for dvergr's agentic programming model.

  Three concepts:

  Agent-as-config (dvergr.agent.config):
    make-agent — declare what an agent is: model, tools, isolation mode.

  Agent-as-task (dvergr.agent.task):
    ask!, spawn!, tell! — run a bounded task with yggdrasil fork/merge isolation.
    merge!, discard!   — accept or reject the agent's work.

  Agent-as-process (dvergr.agent.process):
    create-agent, start! — long-lived reactive process with Happy Eyeballs loop.
    send!, ask, stop!, pause!, resume!, steer! — lifecycle and communication.

  Quick start:
    (require '[dvergr.agent :as agent])

    ;; One-shot task with isolation
    (def coder (agent/make-agent {:name \"coder\" :isolation :sci}))
    (let [result @(agent/ask! coder \"Implement feature X\")]
      (when (agent/successful? result)
        (agent/merge! result)))

    ;; Long-lived process
    (def ctx (agent/create-shared-context))
    (binding [rtc/*execution-context* ctx]
      (def proc (agent/create-agent {:id :worker}))
      (agent/start! proc my-think-fn)
      (agent/send! proc \"Task 1\"))"
  (:require [dvergr.agent.config :as config]
            [dvergr.agent.process :as process]
            [dvergr.agent.task :as task]))

;; Config
(def make-agent config/make-agent)
(def can-spawn? config/can-spawn?)
(def can-use-tool? config/can-use-tool?)
(def isolated? config/isolated?)

;; Task (one-shot)
(def ask! task/ask!)
(def spawn! task/spawn!)
(def tell! task/tell!)
(def merge! task/merge!)
(def discard! task/discard!)
(def successful? task/successful?)
(def extract-result task/extract-result)
(def extract-all-text task/extract-all-text)
(def extract-tool-uses task/extract-tool-uses)
(def parallel task/parallel)
(def race task/race)
(def timeout task/timeout)
(def merge-from-parent! task/merge-from-parent!)

;; Process (long-lived)
(def create-agent process/create-agent)
(def create-shared-context process/create-shared-context)
(def get-datahike-conn process/get-datahike-conn)
(def start! process/start!)
(def stop! process/stop!)
(def pause! process/pause!)
(def resume! process/resume!)
(def steer! process/steer!)
(def send! process/send!)
(def ask process/ask)
(def tell! process/tell!)
(def collect-n process/collect-n)
(def status process/status)
(def running? process/running?)
(def stopped? process/stopped?)
(def paused? process/paused?)
