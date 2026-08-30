(ns dvergr.sandbox.ns.kb
  "SCI injectors — knowledge/world surface: llm (cheap calls), calendar, fulltext
   search, entity graph, and the unified room API. Split out of dvergr.sandbox
   (Phase 4). Subsystems reached via inline require + ns-resolve; entity/room use
   datahike + spindel directly."
  (:require [sci.core :as sci]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as rtc]
            [dvergr.sandbox.ns.doc :as doc]))

(defn add-llm-ns!
  "Expose cheap one-shot LLM calls as 'llm namespace in SCI.

   Agents can write natural Clojure in clojure_eval:

     (require '[llm])
     (llm/summarize transcript {:max-tokens 300})
     (llm/call \"Extract product names:\" content)

   Returns {:text :usage :model} or {:error}. Top-level sandboxes retain the
   historical unbounded surface. A nested authority with
   `:provider-effects? false` removes this provider-spend bypass."
  [sci-ctx & [agent-program-ceiling]]
  (require 'dvergr.tools.llm-call)
  (let [raw-call-fn  @(ns-resolve 'dvergr.tools.llm-call 'cheap-llm-call)
        call-fn      (fn [& args]
                       (when (false? (:provider-effects? agent-program-ceiling))
                         (throw (ex-info
                                 "LLM provider effects exceed this sandbox's delegation ceiling"
                                 {:type ::provider-effects-disallowed})))
                       (apply raw-call-fn args))
        summarize-fn (fn [content & [opts]]
                       (call-fn "Summarize the key points concisely:"
                                content (or opts {})))]
    (sci/add-namespace! sci-ctx 'llm
                        (doc/with-docs
                          {'call      call-fn
                           'summarize summarize-fn}
                          '{call      [([system-prompt content] [system-prompt content opts]) "One-shot call to a CHEAP model — for mechanical language work (extract, classify, rewrite) inside a larger job, not for reasoning you should do yourself. `opts` takes :max-tokens. Available only when this sandbox has provider-effect authority."]
                            summarize [([content] [content opts]) "Summarize text with the cheap model. `opts` takes :max-tokens, e.g. (llm/summarize page {:max-tokens 300})."]}))))

;; (RF5: the calendar folded into the per-room scheduler — see `scheduler/*` +
;; `dvergr.room/schedules`. The standalone calendar subsystem is gone.)

(defn room-ops-map
  "The unified Room-ops map — `create!`/`list`/`get`/`post!`/`messages`/`children`/
   `set-parent!`/`join!`/`leave!`/`delete!`/`fork!`/`merge!`/`discard!`/`diff`/
   `review`/`classify`/`forks`/`participants`/`root` — for the `dvergr.room` SCI
   namespace (mounted, merged with the DB surface, by `dvergr.sandbox.ns.room`).
   Persistent rooms + forks are behind one surface — same for agents, TUI, web."
  [spindel-ctx & [agent-program-ceiling]]
  (require 'dvergr.discourse)
  (require 'dvergr.rooms)
  (require 'dvergr.room.registry)
  (require 'dvergr.room.store)
  (require 'dvergr.rooms.forks)
  (let [fork-diff*      @(ns-resolve 'dvergr.rooms.forks 'fork-diff)
        fork-review*    @(ns-resolve 'dvergr.rooms.forks 'review)
        fork-classify*  @(ns-resolve 'dvergr.rooms.forks 'classify)
        post*           @(ns-resolve 'dvergr.discourse 'post!)
        msg*            @(ns-resolve 'dvergr.discourse 'message)
        join-disc*      @(ns-resolve 'dvergr.discourse 'join)
        leave-disc*     @(ns-resolve 'dvergr.discourse 'leave)
        messages*       @(ns-resolve 'dvergr.discourse 'messages)
        fork-room*      @(ns-resolve 'dvergr.discourse 'fork-room)
        merge-room*     @(ns-resolve 'dvergr.discourse 'merge-room)
        discard*        @(ns-resolve 'dvergr.discourse 'discard)
        create-room!*   @(ns-resolve 'dvergr.rooms 'create-room!)
        join-agent!*    @(ns-resolve 'dvergr.rooms 'join-agent!)
        leave-agent!*   @(ns-resolve 'dvergr.rooms 'leave-agent!)
        set-parent!*    @(ns-resolve 'dvergr.rooms 'set-parent!)
        delete-room!*   @(ns-resolve 'dvergr.rooms 'delete-room!)
        get-by-slug*    @(ns-resolve 'dvergr.rooms 'get-room-by-slug)
        slug->id*       @(ns-resolve 'dvergr.room.store 'slug->room-id)
        rreg-lookup*    @(ns-resolve 'dvergr.room.registry 'lookup)
        rreg-list*      @(ns-resolve 'dvergr.room.registry 'list-rooms)
        rreg-children*  @(ns-resolve 'dvergr.room.registry 'children)
        registry*       (find-ns 'dvergr.room.registry)
        rstore-ns*      (find-ns 'dvergr.room.store)
        ;; Helpers
        resolve-room    (fn [ref]
                          (binding [rtc/*execution-context* spindel-ctx]
                            (cond
                              (and (map? ref) (:bus ref) (:participants ref))
                              ref                                ; already a Room
                              (keyword? ref) (rreg-lookup* ref)
                              (string? ref)  (or (rreg-lookup* ref)
                                                 (rreg-lookup* (slug->id* ref))))))
        ;; ---------- API ----------
        create-fn   (fn [{:keys [title slug type telegram-chat-id agents
                                 agent-ids parent-id]
                          :as _opts}]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (let [aset (set (or agents agent-ids))]
                          (create-room!*
                           (cond-> {:title title
                                    :slug  slug
                                    :type  (or type :internal)
                                    :ctx   spindel-ctx}
                             telegram-chat-id (assoc :telegram-chat-id telegram-chat-id)
                             (seq aset)       (assoc :agent-ids aset)
                             parent-id        (assoc :parent-id parent-id)))
                          {:slug slug :title (or title slug) :agents aset
                           :room (rreg-lookup* (slug->id* slug))})))
        list-fn     (fn [& {:keys [where]}]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (if where (rreg-list* :where where) (rreg-list*))))
        get-fn      resolve-room
        post-fn     (fn [ref {:keys [content from source-user source-username source-user-id]}]
                      (if-let [room (resolve-room ref)]
                        (binding [rtc/*execution-context* (:ctx room)]
                          (post* room (msg* (or from :user) nil content nil
                                            (cond-> {}
                                              source-user      (assoc :source-user source-user)
                                              source-username  (assoc :source-username source-username)
                                              source-user-id   (assoc :source-user-id source-user-id))))
                          {:posted-to (:id room) :content content})
                        {:error (str "Room not found: " ref)}))
        messages-fn (fn [ref & {:keys [limit since]}]
                      (when-let [room (resolve-room ref)]
                        (messages* room (cond-> {} limit (assoc :limit limit)
                                                since (assoc :since since)))))
        children-fn (fn [ref]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (when-let [room (resolve-room ref)]
                          (rreg-children* (:id room)))))
        set-parent-fn (fn [child-ref parent-ref]
                        (let [c (resolve-room child-ref)
                              p (resolve-room parent-ref)]
                          (if (and c p)
                            (do (set-parent!* c p)
                                {:child (:id c) :parent (:id p)})
                            {:error "Child or parent not found"})))
        join-fn     (fn [ref agent-id]
                      (when-let [room (resolve-room ref)]
                        (join-agent!* room agent-id)
                        {:joined agent-id :room (:id room)}))
        leave-fn    (fn [ref agent-id]
                      (when-let [room (resolve-room ref)]
                        (leave-agent!* room agent-id)
                        {:left agent-id :room (:id room)}))
        delete-fn   (fn [ref]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (if-let [room (resolve-room ref)]
                          (do
                            ;; An SCI evaluation cannot synchronously join its
                            ;; own controller/context teardown. Self-deletion is
                            ;; a supervisor effect; subordinate Rooms are safe.
                            (when (identical? spindel-ctx (:ctx room))
                              (throw (ex-info "A Room cannot delete itself from its own SCI runtime"
                                              {:type ::self-delete
                                               :room-id (:id room)})))
                            (let [result (delete-room!* room)]
                              (if (:ok? result)
                                {:deleted (:id room)}
                                (throw (ex-info "Room deletion failed"
                                                {:type ::room-delete-failed
                                                 :room-id (:id room)
                                                 :error (:error result)})))))
                          {:error (str "Room not found: " ref)})))
        fork-fn     (fn fork-fn
                      ([ref] (fork-fn ref {}))
                      ([ref opts]
                       (binding [rtc/*execution-context* spindel-ctx]
                         (when-let [room (resolve-room ref)]
                           (binding [rtc/*execution-context* (:ctx room)]
                             (fork-room* room opts))))))
        merge-fn    (fn [parent fork]
                      (binding [rtc/*execution-context* (:ctx fork)]
                        (merge-room* parent fork)))
        discard-fn  (fn [fork]
                      (binding [rtc/*execution-context* (:ctx fork)]
                        (discard* fork)))
        ;; Merge review — the per-system diff + tier the agent reads to decide.
        diff-fn     (fn [fork-ref]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (some-> (resolve-room fork-ref) fork-diff*)))
        review-fn   (fn [fork-ref]
                      (binding [rtc/*execution-context* spindel-ctx]
                        (some-> (resolve-room fork-ref) fork-review*)))
        forks-fn    (fn []
                      (binding [rtc/*execution-context* spindel-ctx]
                        (rreg-list* :where #(some? (:forked-from @(:meta %))))))
        participants-fn (fn [room]
                          (when room (vec (keys @(:participants room)))))
        root-fn     (fn []
                      (binding [rtc/*execution-context* spindel-ctx]
                        (or (rreg-lookup* :daemon)
                            (rtc/get-state [:dvergr/discourse-root]))))]
    (doc/with-docs
      {'create!      create-fn
       'list         list-fn
       'get          get-fn
       'post!        post-fn
       'messages     messages-fn
       'children     children-fn
       'set-parent!  set-parent-fn
       'join!        join-fn
       'leave!       leave-fn
       'delete!      delete-fn
       'fork!        fork-fn
       'merge!       merge-fn
       'discard!     discard-fn
       'diff         diff-fn
       'review       review-fn
       'classify     fork-classify*
       'forks        forks-fn
       'participants participants-fn
       'root         root-fn}
      '{create!      [([opts]) "Create a persistent room. `opts` takes :slug :title :agents. Rooms are the unit of work: each has its own git repo, knowledge base and schedules."]
        list         [([]) "Every room you can see, as maps."]
        get          [([ref]) "One room by slug or id, or nil."]
        post!        [([ref content]) "Post a message into a room — how you talk to the people and agents in it. `ref` is a slug or id."]
        messages     [([ref] [ref opts]) "Recent messages in a room, newest first."]
        children     [([ref]) "Rooms whose parent is this one."]
        set-parent!  [([child parent]) "Re-parent a room, building the room tree."]
        join!        [([ref] [ref who]) "Join a room so you receive its messages."]
        leave!       [([ref] [ref who]) "Stop receiving a room's messages."]
        delete!      [([ref]) "Delete a room. Destructive — prefer discard! on a fork, or archiving."]
        fork!        [([ref]) "Branch a room into an ISOLATED copy — its own git repo AND database — so you can experiment freely. This is the safe way to attempt a substantial or risky change: fork, work, then merge! or discard!."]
        merge!       [([parent fork]) "Collapse a fork's work back into its parent, surfacing a git + database diff for review. The other half of the fork→test→merge loop."]
        discard!     [([fork]) "Throw a fork away, keeping the parent untouched."]
        diff         [([fork]) "What a fork CHANGED versus its parent — code and data — so you can judge it before merging."]
        review       [([fork]) "An agent review of a fork's changes: the fork tiering + review pipeline, not just a raw diff."]
        classify     [([fork]) "How mergeable a fork is (its tier) — used to route it as a task vs a proposal."]
        forks        [([ref]) "Every fork of a room."]
        participants [([ref]) "Who is in a room — agents and humans."]
        root         [([]) "The root room of the tree. Delegate work through dvergr.agent/hire! so it remains Run-backed and Spindel-composable."]})))
