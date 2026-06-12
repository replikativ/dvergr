(ns dvergr.sandbox.ns.agent
  "SCI injectors — agent identity + work: agents (self/inbox), actors (durable
   identity), skills, tasks, scheduler. Split out of dvergr.sandbox (Phase 4).
   Subsystems reached via inline require + ns-resolve."
  (:require [sci.core :as sci]))

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
                        {'list    list-fn
                         'lookup  lookup-fn
                         'online? online?-fn
                         'by-tag  by-tag-fn})))

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
                                  'dvergr.substrate.git/current-worktree-path))
                                (catch Throwable _ nil)))]
    (sci/add-namespace! sci-ctx 'dvergr.skills
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
                                      (if-let [path (:path (get (load-all* (room-dir)) (str skill-name)))]
                                        (do (promote* path (str by) (str date)) true)
                                        (throw (ex-info (str "no such skill to promote: " skill-name) {}))))})))

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
                        {'list          (fn [& kvs] (apply list-fn conn kvs))
                         'lookup        (fn [id]      (lookup-fn conn id))
                         'online?       (fn [id]      (online?-fn id))
                         'spawn-agent!  (fn [opts]    (spawn-agent-fn conn opts))
                         'spawn-human!  (fn [opts]    (spawn-human-fn conn opts))
                         'dismiss!      (fn [id]      (dismiss-fn conn id))
                         'update!       (fn [id patch] (update-fn conn id patch))
                         'add-skill!    (fn [id skill] (add-skill-fn conn id skill))
                         'remove-skill! (fn [id skill] (remove-skill-fn conn id skill))})))

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
                        {'list      (fn [& kvs] (apply list-fn conn kvs))
                         'lookup    (fn [id]    (lookup-fn conn id))
                         'accept!   (fn [id]    (accept-fn conn id))
                         'complete! (fn [id r]  (complete-fn conn id r))
                         'ignore!   (fn [id]    (ignore-fn conn id))})))

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
  (let [sched-create  @(ns-resolve 'dvergr.scheduler.core 'create-schedule!)
        sched-cancel  @(ns-resolve 'dvergr.scheduler.core 'cancel-schedule!)
        sched-list    @(ns-resolve 'dvergr.scheduler.core 'list-schedules)
        current-room  @(ns-resolve 'dvergr.scheduler.core 'current-room)
        room!         (fn []
                        (or (current-room)
                            (throw (ex-info "No current room — schedules are per-room" {}))))

        every-fn (fn [period & args]
                   (let [[opts agent-id task]
                         (cond
                           (and (keyword? (first args)) (string? (second args)))
                           [{:every period :on (first args) :at (second args)}
                            (nth args 2) (nth args 3)]
                           (string? (first args))
                           [{:every period :at (first args)} (second args) (nth args 2)]
                           (keyword? (first args))
                           [{:every period} (first args) (second args)]
                           :else
                           (throw (ex-info "Invalid schedule args" {:period period :args args})))]
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
                        {'every    every-fn
                         'at       at-fn
                         'interval interval-fn
                         'cancel   (fn [id] (sched-cancel (room!) id))
                         'list     (fn [] (sched-list (room!)))})))


