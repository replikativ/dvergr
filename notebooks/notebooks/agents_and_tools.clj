(ns notebooks.agents-and-tools
  "Clay notebook: a REAL LLM agent in a room, using the `clojure_eval` SCI
   sandbox as a tool (`dvergr.discourse.llm/llm-agent` + `dvergr.discourse/ask`).

   Unlike the other notebooks this one needs a model, so it does NOT execute live
   at render time (CI / GitHub Pages have no key). It shows the runnable code and
   a captured example reply, so the book builds anywhere. Paste the code into a
   dvergr REPL with a provider key to run it yourself."
  (:require [scicloj.kindly.v4.kind :as kind]))

;; # Agents and tools

;; The previous notebooks built the kernel with scripted participants. The point
;; of dvergr is that an **LLM agent is a participant of the same shape** — you
;; swap the scripted `:on-message` for `dvergr.discourse.llm/llm-agent` and
;; nothing else about the room model changes. The agent runs a full turn (model
;; call + tool execution + budget accounting) and posts its reply back like any
;; participant.

;; ## The tool surface: `clojure_eval` *is* the SCI sandbox
;;
;; An agent's main programming surface is **`clojure_eval`** — it evaluates
;; Clojure in an isolated **SCI** context wired to the live dvergr world (rooms,
;; knowledge, intake). It's not a calculator bolted on; it's the same medium the
;; agent is built from, which is what lets an agent inspect and extend the running
;; system. Around it sit the rest of the registry — structured
;; `read_file`/`write_file`/`edit_file`, a test runner, and a **jailed `muschel`
;; shell** (parsed + filesystem-sandboxed, not raw `bash -c`). An agent opts into
;; tools by name via `:tools`.

;; ## Build a room with a coding agent
;;
;; `llm-agent` takes an `:id`, a `:spec` (provider + model + system prompt), and a
;; set of `:tools` (names from the `dvergr.tools` registry). Here the agent gets
;; `clojure_eval` so it can *compute* the answer in the sandbox rather than guess
;; it. `ask` posts a message and awaits exactly one reply (a `Spin[Message]`);
;; `with-room` binds the room's execution context.

(kind/code
 (pr-str
  '(do
     (require '[dvergr.discourse :as d :refer [with-room]]
              '[dvergr.discourse.llm :as llm]
              '[dvergr.model.providers :as providers]
              '[org.replikativ.spindel.spin.combinators :as comb])
     (providers/ensure-initialized!)
     (let [room (d/room :agent-demo)
           spec {:provider :fireworks
                 :model    "accounts/fireworks/models/kimi-k2p6"
                 :system-prompt "You are a terse Clojure assistant. Use clojure_eval to compute."}]
       (with-room room
         (d/join room (llm/llm-agent {:id :coder :spec spec :tools #{:clojure_eval}}))
         (-> (comb/timeout (d/ask room :coder
                             {:content "What is 17 * 23? Compute it with clojure_eval."})
                           90000 {:content "[timed out]"})
             deref :content))))))

;; ## Example reply
;;
;; The agent receives the question, calls `clojure_eval` to evaluate `(* 17 23)`
;; in the SCI sandbox, and replies with the computed result:

(kind/md "> **391** — computed with `clojure_eval`, not guessed.")

;; A full model-plus-tool turn, driven through the same `room` / `ask` primitives
;; as the scripted notebooks. (This block is a captured example — the notebook
;; doesn't call a model at render time; run the code above at a REPL to reproduce
;; it.)

;; ## Budgets and context, automatically
;;
;; Two things happen around that turn without any extra code:
;;
;; - **Budget accounting.** Every turn is metered in microdollars against the
;;   agent's `:budget` (default a small dollar cap). When it crosses a checkpoint
;;   the agent can escalate `:directive/raise-budget` (the escalation pattern from
;;   `programming_model`) instead of running away — cost stays bounded, which is
;;   also the guardrail on recursive agent-spawning.
;; - **Context management.** Long runs auto-prune tool outputs and summarize older
;;   turns as the model's window fills (`dvergr.chat.compaction`), so an agent can
;;   hold a long conversation without overflowing context.
;;
;; Everything composable about the kernel — tagged routing, forks, budgets,
;; streaming — applies unchanged to LLM agents. To give this agent a sandboxed
;; shell or file tools, add their names to `:tools` (e.g. `#{:clojure_eval :shell
;; :read_file}`); the turn loop dispatches them the same way.
