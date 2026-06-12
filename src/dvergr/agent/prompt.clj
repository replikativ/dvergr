(ns dvergr.agent.prompt
  "The single system-prompt assembler for LLM agents — used by BOTH personas
   (`dvergr.discourse.personas/build-persona`) and daemon agents
   (`dvergr.orchestration.daemon`). Before this, the daemon path assembled the
   full prompt (preamble + skills + tool-use guideline + sandbox pointer) while
   personas only got a thin tools listing + sandbox pointer — so personas missed
   the shared-room etiquette, the skills, and the act-don't-narrate guideline.
   This ns owns the STATIC pipeline so both paths produce the same thing.

   The DYNAMIC per-turn planning-mode guideline is NOT baked in here — it is
   appended per turn by the turn path (gated on `commands/room-mode`), since a
   room can flip between /plan and /build between turns. `planning-mode-guideline`
   lives here as the canonical text for that appender."
  (:require [clojure.string :as str]
            [dvergr.orchestration.skills :as skills]))

(def tool-use-guideline
  "Operational guideline appended to every tool-bearing agent's system prompt.
   Model-agnostic; targets the narrate-then-stop and re-fetch failure modes of
   weaker tool-using models. The turn loop ends a turn the moment the model
   replies with no tool call (the way LLMs are trained), so an agent that
   announces work instead of doing it silently stalls — this tells it not to."
  (str "## Acting\n\n"
       "You act ONLY by calling tools — describing an action does not perform "
       "it. Your turn ENDS the moment you reply with text and no tool call, so:\n"
       "- To do or continue ANY work, call the tool in THIS message. Never end "
       "a message by announcing what you'll do next ('Now let me…', 'Next "
       "I'll…') — either call the tool now, or give your final answer if the "
       "task is fully done.\n"
       "- For multi-step tasks, call the tools for each step in sequence; don't "
       "stop after one step to describe the rest.\n"
       "- You already have the results of tools you called earlier in this "
       "conversation — reuse them; do not re-fetch the same thing."))

(defn now-note
  "A per-turn system note stating TODAY'S DATE, so the model anchors 'today',
   'this week', 'recent', deadlines and current-event questions to reality
   instead of its training cutoff (the cause of agents confidently answering
   with stale years/events). Terse on purpose — modelled on how Claude Code
   communicates the date (a single `Today's date is …` line), not a paragraph
   of guidance. Computed at call time and appended as a per-turn `:system-suffix`
   (never baked into the persisted prompt) so it stays fresh across days."
  []
  (let [now (java.time.ZonedDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "EEEE, yyyy-MM-dd")]
    (str "Today's date is " (.format now fmt) ".")))

(def planning-mode-guideline
  "Appended per turn when the room is in /plan mode (commands/room-mode → :plan).
   The turn path owns the gating; this is just the canonical text."
  (str "## Planning mode\n\n"
       "This room is in PLANNING mode. Think through the approach and present a "
       "concise plan (goal, steps, risks, files to touch). Do NOT make changes, "
       "run mutating tools, or commit — wait for the user to switch to build mode "
       "(/build) or approve. Read-only investigation is fine."))

(def discourse-preamble
  "Leads EVERY agent's system prompt. Teaches the shared-room discourse model and
   the `[SKIP]` silence convention — the soft half of multi-agent loop control
   (see doc/multi-agent-reply-gating.md). Without it agents have no notion that
   they share a room with others and will chime in on everything, which in a
   multi-agent room produces a reply cascade."
  (str "## You are in a shared room\n\n"
       "You are one participant in a dvergr room that may contain other people "
       "AND other agents. Everyone sees every message. You are not obligated to "
       "respond to a message just because you can see it.\n\n"
       "**Reply only when you can add genuine, non-redundant value.** If you have "
       "nothing substantive to add — you'd merely be acknowledging, agreeing, "
       "thanking, restating what was already said, or chiming in for its own sake "
       "— then stay silent by replying with exactly:\n\n"
       "    [SKIP]\n\n"
       "and nothing else. Choosing `[SKIP]` is the correct, preferred behaviour in "
       "those cases; it is not a failure.\n\n"
       "Be especially restrained with **other agents' messages**: do not reply to "
       "another agent merely to acknowledge, agree, or keep a conversation going. "
       "Engage a peer only when you have a concrete, necessary contribution they "
       "actually need. Two agents trading pleasantries is exactly what to avoid.\n\n"
       "**Never relay, forward, or answer on another participant's behalf.** "
       "Everyone already sees every message, so there is nothing to forward and no "
       "one to route to. If a message is meant for another agent, or sits in their "
       "domain, reply exactly `[SKIP]` and let them answer it directly — do not "
       "acknowledge it, do not 'loop them in', do not post a message on their behalf, "
       "and never impersonate a user. Act only as yourself.\n\n"
       "Prefer one good message over several. When you do reply, be concise.\n\n"
       "The messages above are a LIVE conversation you are part of — not a "
       "transcript to summarise or analyse. Each line from someone else is shown "
       "prefixed `[name · time]` so you know who is speaking and when; the system "
       "adds that prefix. Reply as yourself, in your own voice, with just your "
       "message — the system adds your name/time, so you don't write a `[name · "
       "time]` prefix, and you never speak as or for another participant."))

(defn- tool-name-str
  "Normalize a tool name to the string the registry/model use: keywords lose
   their leading colon and have dashes→underscores; strings pass through."
  [x]
  (if (keyword? x) (str/replace (name x) "-" "_") (str x)))

(defn assemble-system-prompt
  "Build an agent's STATIC system prompt, the same way for personas and daemon
   agents:

     discourse-preamble  ── shared-room etiquette + the [SKIP] convention
     \\n---\\n base-prompt ── the persona/agent's own role text
     + skills            ── eligible skills injected by tool name
     + (## Runtime …)     ── isolation note, only when `:isolation` is supplied
     + tool-use-guideline ── when the agent has any tools
     + sandbox pointer    ── when `clojure_eval` is among the tools

   `tools` may be a tool map (name→def, string keys) OR a set/vector of names.
   `isolation` is optional (`:native` / `:sci`); omit it to leave the proven
   daemon prompt byte-identical. `:room-dir` (optional) is a room's sandbox-repo
   path — when given, skills the room itself defines are injected too. The
   dynamic planning-mode guideline is appended elsewhere (per turn). sandbox
   pointer via requiring-resolve to avoid a cycle."
  [base-prompt {:keys [tools isolation room-dir]}]
  (let [names   (mapv tool-name-str (if (map? tools) (keys tools) (or tools [])))
        nameset (set names)
        eval?   (contains? nameset "clojure_eval")
        ;; When not pinned, resolve the current room's sandbox-repo best-effort
        ;; (nil at the daemon root / no room ctx → global skills only), so an
        ;; agent assembled within a room sees that room's own skills.
        room-dir (or room-dir
                     (try ((requiring-resolve 'dvergr.substrate.git/current-worktree-path))
                          (catch Throwable _ nil)))]
    (cond-> (skills/inject-skills
             (str discourse-preamble "\n\n---\n\n" base-prompt)
             names room-dir)
      isolation     (str "\n\n## Runtime\n\nIsolation: " (name isolation)
                         (if (= :native (keyword isolation))
                           " — full Clojure eval at the system root (trusted)."
                           " — sandboxed Clojure eval (safe default)."))
      (seq nameset) (str "\n\n" tool-use-guideline)
      eval?         (str "\n\n" @(requiring-resolve 'dvergr.sandbox/sandbox-prompt-pointer)))))
