(ns dvergr.sandbox.ns.agent
  "SCI injectors — agent identity + work: agents (self/inbox), actors (durable
   identity), skills, tasks, scheduler. Split out of dvergr.sandbox (Phase 4).
   Subsystems reached via inline require + ns-resolve.

   Each `sci/add-namespace!` map is wrapped in `doc/with-docs` so the injected
   closures carry `:doc`/`:arglists` — without it `(clojure.repl/doc …)` and
   `(find-doc …)` answer nothing for them inside the sandbox. See
   `dvergr.sandbox.ns.doc`."
  (:require [sci.core :as sci]
            [dvergr.sandbox.ns.doc :as doc]
            [org.replikativ.spindel.engine.core :as ec]))

(defn add-programming-ns!
  "Expose immutable AgentDefs and Run-backed hiring as `dvergr.agent` in SCI.

   The namespace deliberately has no hidden current roster. `roster`,
   `make-agent`, and `revise-agent` return ordinary immutable values, so a
   Spindel computation can branch with a different team without coordinating a
   mutable registry. `hire!` is the explicit effect boundary: it resolves this
   sandbox's Room, starts a durable Run in the Room's execution context, and
   returns an opaque RunHandle whose native observer Spin is explicit. When
   the authority map supplies `:parent-run`, `hire!` uses it as the structural
   parent unless the caller explicitly supplies one.

   Usage:
     (require '[dvergr.agent :as agent]
              '[org.replikativ.spindel.spin.cps :refer [spin]]
              '[org.replikativ.spindel.effects.await :refer [await]])
     (let [team (-> (agent/roster)
                    (agent/make-agent
                     {:id :analyst
                      :skills #{:research}
                      :program {:kind :echo}}))]
       @(spin (-> (await (agent/result-spin
                          (agent/hire! team :analyst {:task :inspect})))
                  :run/value)))"
  [sci-ctx room-id spindel-ctx agent-program-ceiling]
  (let [make-roster*   (requiring-resolve 'dvergr.agent.roster/make-roster)
        make-agent*    (requiring-resolve 'dvergr.agent.roster/make-agent)
        revise-agent*  (requiring-resolve 'dvergr.agent.roster/revise-agent)
        lookup-agent*  (requiring-resolve 'dvergr.agent.roster/agent)
        agent-ref*     (requiring-resolve 'dvergr.agent.roster/agent-ref)
        agents*        (requiring-resolve 'dvergr.agent.roster/agents)
        select-agents* (requiring-resolve 'dvergr.agent.roster/select-agents)
        hire*          (requiring-resolve 'dvergr.agent.program/hire!)
        observe*       (requiring-resolve 'dvergr.agent.program/observe)
        cancel*        (requiring-resolve 'dvergr.agent.program/cancel!)
        run-id*        (requiring-resolve 'dvergr.agent.program/run-id)
        result-spin*   (requiring-resolve 'dvergr.agent.program/result-spin)
        owned-result-spin* (requiring-resolve 'dvergr.agent.program/owned-result-spin)
        room-lookup*   (requiring-resolve 'dvergr.room.registry/lookup)
        current-room   (fn []
                         (when room-id
                           (binding [ec/*execution-context* spindel-ctx]
                             (room-lookup* room-id))))
        room!          (fn []
                         (or (current-room)
                             (throw (ex-info
                                     "No current Room — agent execution is room-scoped"
                                     {:type ::no-current-room
                                      :room-id room-id}))))
        hire-fn        (fn [roster agent-ref opts]
                         (let [room (room!)
                               definition (lookup-agent* roster agent-ref)
                               kind (get-in definition [:agent/program :kind])
                               allowed-kinds (:program-kinds agent-program-ceiling)]
                           (when (and allowed-kinds
                                      (not (contains? allowed-kinds kind)))
                             (throw (ex-info
                                     "Child program exceeds this sandbox's delegation ceiling"
                                     {:type ::program-ceiling-exceeded
                                      :agent-ref agent-ref
                                      :program-kind kind
                                      :allowed-program-kinds allowed-kinds})))
                           (binding [ec/*execution-context* (:ctx room)]
                             (hire* room roster agent-ref
                                    (if (and (:parent-run agent-program-ceiling)
                                             (not (contains? opts :parent-run)))
                                      (assoc opts :parent-run
                                             (:parent-run agent-program-ceiling))
                                      opts)))))
        observe-fn     (fn [handle-or-id]
                         (let [room (room!)]
                           (binding [ec/*execution-context* (:ctx room)]
                             (observe* room handle-or-id))))
        cancel-fn      (fn [handle-or-id]
                         ;; Run cancellation tokens are process-local. Binding
                         ;; the Room ctx keeps this boundary consistent with
                         ;; hire/observe and ready for a Spindel-local registry.
                         (let [room (room!)]
                           (binding [ec/*execution-context* (:ctx room)]
                             (cancel* room handle-or-id))))]
    (sci/add-namespace!
     sci-ctx 'dvergr.agent
     (doc/with-docs
       {'roster       make-roster*
        'make-agent   make-agent*
        'revise-agent revise-agent*
        'lookup       lookup-agent*
        'ref          agent-ref*
        'list         agents*
        'select       select-agents*
        'hire!        hire-fn
        'observe      observe-fn
        'cancel!      cancel-fn
        'run-id       run-id*
        'result-spin  result-spin*
        'owned-result-spin owned-result-spin*}
       '{roster       [([] [opts]) "Create an immutable Roster value. Options may include portable :id, :defaults, :scope, and :metadata data."]
         make-agent   [([roster spec]) "Return a NEW Roster containing `spec`; use {:id :a :program {:kind :echo}}, {:kind :scripted :reply value}, or {:kind :llm} plus :model-policy and :tools. Pure: input unchanged."]
         revise-agent [([roster id patch]) "Return a NEW Roster with AgentDef `id` revised and its version incremented."]
         lookup       [([roster id-or-ref]) "Resolve an AgentDef by keyword id or versioned AgentRef. A stale versioned ref is an error."]
         ref          [([agent-def]) "Return the stable {:agent/id :agent/version} reference for an AgentDef."]
         list         [([roster]) "All AgentDefs in a Roster, deterministically ordered by id."]
         select       [([roster selector]) "Select AgentDefs by :id, :status, :skill/:skills, and exact portable :where data."]
         hire!        [([roster agent-ref opts]) "Durably start one AgentDef in the current Room: (hire! team :a {:task value}). Returns a RunHandle; opts may also include :from and structural :parent-run."]
         observe      [([handle-or-run-id]) "Read the current Room's durable Run projection for a RunHandle or UUID."]
         cancel!      [([handle-or-run-id]) "Request cooperative cancellation of exactly one live Run. Returns true when the Run was found."]
         run-id       [([handle]) "Return the durable Run UUID represented by a RunHandle."]
         result-spin  [([handle]) "Return a passive Spindel observer Spin for a RunHandle. Multiple observers may await it; cancelling an observer does not cancel the Run."]
         owned-result-spin [([handle]) "Return an ownership-coupled result Spin. Cancelling this observer also cancels the underlying Run; use only when the observer owns that child execution."]}))))

(defn add-agents-ns!
  "Expose the agent registry as 'agents namespace in SCI.

   Lets var ground-truth which personalities are actually running in
   THIS daemon (not just which ones the profile mentions). When the
   user asks 'have Skald draft a post', var should `(agents/list)`
   first to confirm Skald is online before calling `(room/join! …)`.

   Usage:
     (require '[agents])
     (agents/list)                       ; all registered agents
     (agents/lookup :skald)               ; full entry or nil
     (agents/online? :huginn)             ; convenience boolean
     (agents/by-tag :coding)              ; ids matching a tag"
  [sci-ctx]
  (require 'dvergr.actors)
  (let [online-actors* @(ns-resolve 'dvergr.actors 'online-actors)
        online?*       @(ns-resolve 'dvergr.actors 'online?)
        list-fn      (fn [] (online-actors*))
        lookup-fn    (fn [id] (some #(when (= id (:id %)) %) (online-actors*)))
        online?-fn   (fn [id] (online?* id))
        by-tag-fn    (fn [tag] (filterv #(contains? (:tags %) tag) (online-actors*)))]
    (sci/add-namespace! sci-ctx 'dvergr.agents
                        (doc/with-docs
                          {'list    list-fn
                           'lookup  lookup-fn
                           'online? online?-fn
                           'by-tag  by-tag-fn}
                          '{list    [([]) "Every agent currently ONLINE in this daemon, as a vector of entries. Ground-truth for who can actually take work right now — a profile mentioning an agent does not mean it is running."]
                            lookup  [([id]) "The full entry for one agent id (e.g. :skald), or nil if it is not online."]
                            online? [([id]) "Whether an agent id is running right now. Check before dispatching work to it."]
                            by-tag  [([tag]) "Online agents whose :tags contain `tag` (e.g. :coding) — a vector, possibly empty."]}))))

(defn add-skills-ns!
  "Expose the skill registry + dispatch as 'skills namespace in SCI.

   Usage:
     (require '[skills])
     (skills/all)                        ; every skill on disk
     (skills/find :research)             ; skill maps providing :research
     (skills/providers :research)        ; actor-ids that declare :research
     (skills/rank :research)             ; ranked online providers
     (skills/dispatch :research)         ; the single best provider (actor map or nil)"
  [sci-ctx conn]
  (require 'dvergr.orchestration.skills)
  (let [load-all*   @(ns-resolve 'dvergr.orchestration.skills 'load-all)
        list-fn     @(ns-resolve 'dvergr.orchestration.skills 'list-skills)
        read-skill* @(ns-resolve 'dvergr.orchestration.skills 'read-skill)
        find-prov   @(ns-resolve 'dvergr.orchestration.skills 'find-providers)
        rank-prov   @(ns-resolve 'dvergr.orchestration.skills 'rank-providers)
        dispatch    @(ns-resolve 'dvergr.orchestration.skills 'dispatch-target)
        dispatch!*  @(ns-resolve 'dvergr.orchestration.skills 'dispatch!)
        author*     @(requiring-resolve 'dvergr.discourse.definitions/author!)
        promote*    @(requiring-resolve 'dvergr.discourse.definitions/promote!)
        ;; Resolved lazily at call time: an agent runs in its ROOM's execution
        ;; context, so this returns the room's sandbox-repo path — letting
        ;; `all`/`read` see skills the room itself defines (highest precedence)
        ;; and `author!`/`promote!` write into the room's own repo.
        room-dir    (fn [] (try ((requiring-resolve
                                  'dvergr.sandbox.workspace/workspace-root))
                                (catch Throwable _ nil)))]
    (sci/add-namespace! sci-ctx 'dvergr.skills
                        (doc/with-docs
                          {'all       (fn [] (load-all* (room-dir)))
                         ;; Progressive disclosure: the system prompt shows a
                         ;; brief index; pull a skill's FULL instructions here.
                           'read      (fn [skill-name] (read-skill* skill-name (room-dir)))
                           'find      (fn [provides-tag]
                                        (vec (list-fn :provides provides-tag)))
                           'providers (fn [skill] (find-prov conn skill))
                           'rank      (fn [skill] (rank-prov conn skill))
                           'dispatch  (fn [skill] (dispatch conn skill))
                           'dispatch! (fn [skill opts]
                                        (dispatch!* conn skill opts))
                         ;; Authoring lifecycle (writes into THIS room's repo —
                         ;; versioned + forkable + mergeable). Agent-authored
                         ;; skills land `vetted: false`, so the vetting gate keeps
                         ;; them out of prompts until a reviewer promotes them.
                           'author!   (fn [skill-name frontmatter body]
                                        (if-let [dir (room-dir)]
                                          (author* "skills" dir (str skill-name) frontmatter (str body))
                                          (throw (ex-info "skills/author! needs a room sandbox repo (no room ctx bound)" {}))))
                         ;; Lift external content (an openclaw/Claude skill, a URL
                         ;; you fetched) into the room as an UNVETTED skill.
                           'lift!     (fn [skill-name source body]
                                        (if-let [dir (room-dir)]
                                          (author* "skills" dir (str skill-name)
                                                   {:source (str source) :vetted false} (str body))
                                          (throw (ex-info "skills/lift! needs a room sandbox repo (no room ctx bound)" {}))))
                         ;; Promote a room skill to vetted (reviewer action).
                           'promote!  (fn [skill-name by date]
                                        (if-let [definition (get (load-all* (room-dir)) (str skill-name))]
                                          (do (promote* definition (str by) (str date)) true)
                                          (throw (ex-info (str "no such skill to promote: " skill-name) {}))))}
                          '{all       [([]) "Every skill visible here — on disk plus any this room defines (the room's own take precedence). A map of skill-name → definition."]
                            read      [([skill-name]) "The FULL instructions for one skill. The system prompt carries only a brief index; pull the body with this before following a skill."]
                            find      [([provides-tag]) "Skill definitions that provide `provides-tag` (e.g. :research) — a vector, possibly empty."]
                            providers [([skill]) "Actor-ids that declare they can perform `skill`, whether or not they are online."]
                            rank      [([skill]) "Providers of `skill` ranked by suitability, ONLINE ones only."]
                            dispatch  [([skill]) "The single best online provider for `skill` (an actor map), or nil if nobody can take it."]
                            dispatch! [([skill opts]) "Actually hand `skill` to its best provider. `opts` carries the payload for the receiving actor."]
                            author!   [([skill-name frontmatter body]) "Write a NEW skill into this room's repo (versioned, forkable, mergeable). Lands `vetted: false`, so it stays out of prompts until a reviewer promotes it. Needs a room ctx."]
                            lift!     [([skill-name source body]) "Import external content (another agent's skill, a fetched URL) into the room as an UNVETTED skill, recording `source`. Needs a room ctx."]
                            promote!  [([skill-name by date]) "Mark a room skill vetted — a REVIEWER action; this is what lets it appear in prompts. Throws if there is no such skill."]}))))

(defn add-actors-ns!
  "Expose the durable actor table as 'actors namespace in SCI.

   The runtime 'agents namespace (above) answers \"who is alive right
   now\" by consulting the in-context registry. 'actors answers \"who
   does the system know about\" — including offline / retired actors.
   It also lets var spawn new agents and dismiss them, with changes
   persisted to Datahike.

   Usage:
     (require '[actors])
     (actors/list)                          ; every durable actor
     (actors/list :kind :agent :status :online)
     (actors/lookup :var)                   ; the durable row
     (actors/online? :var)                  ; runtime check
     (actors/spawn-agent! {:id :scribe
                            :name \"Scribe\"
                            :profile-ref \"scribe.md\"
                            :skills #{:writing}
                            :config {:provider :fireworks
                                     :model \"...\"}})
     (actors/dismiss! :scribe)              ; flag :status :retired
     (actors/update! :scribe {:skills #{:prose :writing}})
     (actors/add-skill! :scribe :prose)
     (actors/remove-skill! :scribe :writing)"
  [sci-ctx conn]
  (require 'dvergr.actors)
  (let [list-fn         @(ns-resolve 'dvergr.actors 'list-actors)
        lookup-fn       @(ns-resolve 'dvergr.actors 'lookup)
        online?-fn      @(ns-resolve 'dvergr.actors 'online?)
        spawn-agent-fn  @(ns-resolve 'dvergr.actors 'spawn-agent!)
        spawn-human-fn  @(ns-resolve 'dvergr.actors 'spawn-human!)
        dismiss-fn      @(ns-resolve 'dvergr.actors 'dismiss!)
        update-fn       @(ns-resolve 'dvergr.actors 'update-actor!)
        add-skill-fn    @(ns-resolve 'dvergr.actors 'add-skill!)
        remove-skill-fn @(ns-resolve 'dvergr.actors 'remove-skill!)]
    (sci/add-namespace! sci-ctx 'dvergr.actors
                        (doc/with-docs
                          {'list          (fn [& kvs] (apply list-fn conn kvs))
                           'lookup        (fn [id]      (lookup-fn conn id))
                           'online?       (fn [id]      (online?-fn id))
                           'spawn-agent!  (fn [opts]    (spawn-agent-fn conn opts))
                           'spawn-human!  (fn [opts]    (spawn-human-fn conn opts))
                           'dismiss!      (fn [id]      (dismiss-fn conn id))
                           'update!       (fn [id patch] (update-fn conn id patch))
                           'add-skill!    (fn [id skill] (add-skill-fn conn id skill))
                           'remove-skill! (fn [id skill] (remove-skill-fn conn id skill))}
                          '{list          [([] [& {:keys [kind status]}]) "Every DURABLE actor the system knows — including offline and retired ones (contrast dvergr.agents/list, which is who is alive now). Filter with :kind (:agent/:human) and :status (e.g. :online, :retired)."]
                            lookup        [([id]) "The durable row for one actor id, or nil. Persisted state, not runtime state."]
                            online?       [([id]) "Runtime check — is this actor actually running now?"]
                            spawn-agent!  [([opts]) "Create a NEW agent, persisted to Datahike. `opts` takes :id :name :profile-ref :skills :config (provider/model). This grows the roster; prefer an existing agent when one fits."]
                            spawn-human!  [([opts]) "Register a HUMAN participant as an actor, so work can be assigned to and tracked for them."]
                            dismiss!      [([id]) "Retire an actor — flags :status :retired rather than deleting, so its history survives."]
                            update!       [([id patch]) "Merge `patch` into an actor's durable row (e.g. {:skills #{:prose :writing}})."]
                            add-skill!    [([id skill]) "Declare that an actor can perform `skill` — this is what makes it show up in dvergr.skills/providers."]
                            remove-skill! [([id skill]) "Withdraw a skill declaration from an actor."]}))))

(defn add-tasks-ns!
  "Expose the task ledger as 'tasks namespace in SCI.

   Tasks are persistent rows for skill dispatches to non-agent actors
   (humans now; externals in phase D-externals). Agents just react to
   inbox messages — they don't need a task row.

   Usage:
     (require '[tasks])
     (tasks/list)                      ; every task
     (tasks/list :actor-id :alice :status :pending)
     (tasks/lookup task-uuid)
     (tasks/accept!   task-uuid)
     (tasks/complete! task-uuid \"done — here's what I found\")
     (tasks/ignore!   task-uuid)"
  [sci-ctx conn]
  (require 'dvergr.orchestration.tasks)
  (let [list-fn     @(ns-resolve 'dvergr.orchestration.tasks 'list-tasks)
        lookup-fn   @(ns-resolve 'dvergr.orchestration.tasks 'lookup)
        accept-fn   @(ns-resolve 'dvergr.orchestration.tasks 'accept!)
        complete-fn @(ns-resolve 'dvergr.orchestration.tasks 'complete!)
        ignore-fn   @(ns-resolve 'dvergr.orchestration.tasks 'ignore!)]
    (sci/add-namespace! sci-ctx 'dvergr.tasks
                        (doc/with-docs
                          {'list      (fn [& kvs] (apply list-fn conn kvs))
                           'lookup    (fn [id]    (lookup-fn conn id))
                           'accept!   (fn [id]    (accept-fn conn id))
                           'complete! (fn [id r]  (complete-fn conn id r))
                           'ignore!   (fn [id]    (ignore-fn conn id))}
                          '{list      [([] [& {:keys [actor-id status]}]) "The shared task ledger — persistent rows for work dispatched to non-agent actors (humans). Filter with :actor-id and :status (e.g. :pending). Agents themselves just react to inbox messages and need no task row."]
                            lookup    [([id]) "One task by its uuid, or nil."]
                            accept!   [([id]) "Claim a task — marks it accepted so nobody else picks it up."]
                            complete! [([id result]) "Finish a task, recording `result` (a string describing what was done/found)."]
                            ignore!   [([id]) "Decline a task, leaving it for someone else."]}))))

(defn add-scheduler-ns!
  "Expose scheduling as 'scheduler namespace in SCI.

   Schedules are per-room (RF5): each call operates on the CURRENT room (the one
   the agent's sandbox is running in). Agents can schedule themselves or other
   agents in that room:
     (require '[scheduler])
     (scheduler/every :day \"09:00\" :huginn \"Run morning intake sweep\")
     (scheduler/every :week :monday \"14:00\" :analyst \"Weekly market review\")
     (scheduler/at \"2026-04-01T09:00\" :var \"April Fools reminder\")
     (scheduler/cancel schedule-id)
     (scheduler/list)"
  [sci-ctx]
  (require 'dvergr.scheduler.core)
  (let [documented
        ;; `dvergr.sandbox` already reports `:arglists`/`:doc` off each injected
        ;; value, and `dev/doc` prints them — but a raw `(fn …)` carries no
        ;; metadata, so everything below contributed nothing and the docstring
        ;; on THIS function never reached the sandbox. An agent's only way to
        ;; learn a signature was to call it and read the error.
        (fn [sym arglists doc f]
          (vary-meta f merge {:name sym :arglists arglists :doc doc}))
        sched-create  @(ns-resolve 'dvergr.scheduler.core 'create-schedule!)
        sched-cancel  @(ns-resolve 'dvergr.scheduler.core 'cancel-schedule!)
        sched-list    @(ns-resolve 'dvergr.scheduler.core 'list-schedules)
        current-room  @(ns-resolve 'dvergr.scheduler.core 'current-room)
        room!         (fn []
                        (or (current-room)
                            (throw (ex-info "No current room — schedules are per-room" {}))))

        every-fn (fn [period & args]
                   ;; Dispatch on ARITY, not on the types of the first two args.
                   ;;
                   ;; The type-based cond could not tell `(every :day :var "task")`
                   ;; from `(every :week :monday "14:00" :agent "task")` — both open
                   ;; (keyword, string) — so the 4-arg branch swallowed the 2-arg
                   ;; form and then ran `(nth args 2)` off the end. That surfaced as
                   ;; a bare IndexOutOfBoundsException with a nil message, and with
                   ;; no arglists to consult an agent reasonably concluded the
                   ;; function was broken. It was not: both documented forms work.
                   ;; The 2-arg form was genuinely unreachable, and is now reachable.
                   (let [n (count args)
                         wrong (fn []
                                 (throw (ex-info
                                         (str "scheduler/every: cannot read these "
                                              n " argument(s) after the period. Expected one of:\n"
                                              "  (every period agent-id task)\n"
                                              "  (every period \"HH:MM\" agent-id task)\n"
                                              "  (every period :day-of-week \"HH:MM\" agent-id task)\n"
                                              "where agent-id is a keyword and task a string.")
                                         {:period period :args (vec args)})))
                         [opts agent-id task]
                         (case n
                           2 [{:every period} (first args) (second args)]
                           3 [{:every period :at (first args)} (second args) (nth args 2)]
                           4 [{:every period :on (first args) :at (second args)}
                              (nth args 2) (nth args 3)]
                           (wrong))]
                     ;; Check the SHAPE, not just the count. Arity alone still lets
                     ;; `(every :day "07:00" :var)` through as agent-id "07:00" and
                     ;; task :var, which only fails later against create-schedule!'s
                     ;; `(keyword? agent-id)` precondition — an AssertionError naming
                     ;; neither the caller nor what it should have passed.
                     (when-not (and (keyword? agent-id) (string? task)
                                    (or (not (:at opts)) (string? (:at opts)))
                                    (or (not (:on opts)) (keyword? (:on opts))))
                       (wrong))
                     (sched-create (room!)
                                   {:agent-id agent-id
                                    :task task
                                    :schedule opts
                                    :description (str "Every " (name period)
                                                      (when (:at opts) (str " at " (:at opts)))
                                                      (when (:on opts) (str " on " (name (:on opts)))))})))

        at-fn (fn [datetime agent-id task]
                (sched-create (room!)
                              {:agent-id agent-id :task task
                               :schedule {:at datetime :once true}
                               :description (str "One-shot at " datetime)}))

        interval-fn (fn [ms agent-id task]
                      (sched-create (room!)
                                    {:agent-id agent-id :task task
                                     :interval-ms ms
                                     :description (str "Every " (/ ms 60000.0) " minutes")}))]
    (sci/add-namespace! sci-ctx 'dvergr.scheduler
                        {'every
                         (documented 'every
                                     '([period agent-id task]
                                       [period at agent-id task]
                                       [period day-of-week at agent-id task])
                                     (str "Recurring schedule in THIS room. `period` is :day/:week/…, "
                                          "`at` is \"HH:MM\" wall-clock, `day-of-week` a keyword like "
                                          ":monday. e.g. (every :day \"09:00\" :huginn \"Morning sweep\").")
                                     every-fn)
                         'at
                         (documented 'at
                                     '([iso-datetime agent-id task])
                                     (str "One-shot at a FULL ISO datetime — \"2026-04-01T09:00\", not "
                                          "\"09:00\". For a daily wall-clock time use `every` instead.")
                                     at-fn)
                         'interval
                         (documented 'interval
                                     '([ms agent-id task])
                                     "Repeat every `ms` milliseconds."
                                     interval-fn)
                         ;; CODE schedules — the durable-pipeline primitive:
                         ;; the form evals in YOUR sandbox on each fire
                         ;; (deterministic, no LLM turn). Put the fns in a
                         ;; namespace in your workspace repo, then e.g.
                         ;; (dvergr.scheduler/create
                         ;;   {:agent-id :me :schedule {:every :day :at \"07:00\"}
                         ;;    :code \"(require 'intake.news)(intake.news/scan!)\"
                         ;;    :description \"morning news scan\"})
                         ;; Cadence forms: {:every :day :at \"07:00\"} (daily at
                         ;; a wall-clock time), {:every :hour :n 4} (every 4h —
                         ;; :n multiplies :minute/:hour/:day/:week into a fixed
                         ;; interval), {:every-ms N}, {:at \"ISO\" :once true}.
                         ;; Unknown keys are REJECTED (no silent wrong cadence).
                         'create
                         (documented 'create
                                     '([cfg])
                                     (str "Richest form. cfg = {:agent-id kw :task \"…\" | :code \"…\" "
                                          ":schedule {…} | :interval-ms N :description \"…\"}. `:code` "
                                          "evals in your sandbox on each fire with no LLM turn. "
                                          "Unknown keys are REJECTED.")
                                     (fn [cfg] (sched-create (room!) cfg)))
                         'cancel
                         (documented 'cancel
                                     '([schedule-id])
                                     "Deactivate a schedule BY ID — the uuid itself, not the map `list` returns."
                                     (fn [id] (sched-cancel (room!) id)))
                         'list
                         (documented 'list
                                     '([])
                                     "Active schedules in this room, as maps carrying :id :kind :next-fire …"
                                     (fn [] (sched-list (room!))))})))
