(ns dvergr.sandbox.ns.kb
  "SCI injectors — knowledge/world surface: llm (cheap calls), calendar, fulltext
   search, entity graph, and the unified room API. Split out of dvergr.sandbox
   (Phase 4). Subsystems reached via inline require + ns-resolve; entity/room use
   datahike + spindel directly."
  (:require [dvergr.substrate.load :as load]
            [sci.core :as sci]
            [datahike.api :as dh]
            [dvergr.runtime.ctx :as runtime-ctx]
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
  (load/require! 'dvergr.tools.llm-call)
  (let [raw-call-fn  @(ns-resolve 'dvergr.tools.llm-call 'cheap-llm-call)
        call-fn      (fn [& args]
                       (when (false? (:provider-effects? agent-program-ceiling))
                         (throw (ex-info
                                 "LLM provider effects exceed this sandbox's delegation ceiling"
                                 {:type ::provider-effects-disallowed})))
                       ;; The documented 2-arity: default opts.
                       (apply raw-call-fn (cond-> (vec args) (= 2 (count args)) (conj {}))))
        summarize-fn (fn [content & [opts]]
                       (call-fn "Summarize the key points concisely:"
                                content (or opts {})))]
    (sci/add-namespace! sci-ctx 'llm
                        (doc/with-docs
                          {'call      call-fn
                           'summarize summarize-fn}
                          '{call      [([system-prompt content] [system-prompt content opts]) "One-shot call to a CHEAP model — for mechanical language work (extract, classify, rewrite) inside a larger job, not for reasoning you should do yourself. `opts` takes :max-tokens. Available only when this sandbox has provider-effect authority."
                                       [:=> [:cat :string [:maybe :string]
                                             [:? [:map [:max-tokens {:optional true} :int]
                                                  [:model {:optional true} :string]
                                                  [:system {:optional true} :string]]]]
                                        [:or [:map [:text :string] [:usage [:maybe :map]] [:model :string]]
                                         [:map [:error [:maybe :string]]]]]]
                            summarize [([content] [content opts]) "Summarize text with the cheap model. `opts` takes :max-tokens, e.g. (llm/summarize page {:max-tokens 300})."
                                       [:=> [:cat [:maybe :string]
                                             [:? [:maybe [:map [:max-tokens {:optional true} :int]
                                                          [:model {:optional true} :string]
                                                          [:system {:optional true} :string]]]]]
                                        [:or [:map [:text :string] [:usage [:maybe :map]] [:model :string]]
                                         [:map [:error [:maybe :string]]]]]]}))))

;; (RF5: the calendar folded into the per-room scheduler — see `scheduler/*` +
;; `dvergr.room/schedules`. The standalone calendar subsystem is gone.)

(defn room-ops-map
  "The unified Room-ops map — `create!`/`list`/`get`/`post!`/`messages`/`children`/
   `set-parent!`/`join!`/`leave!`/`delete!`/`fork!`/`merge!`/`discard!`/`diff`/
   `review`/`classify`/`forks`/`participants`/`root` — for the `dvergr.room` SCI
   namespace (mounted, merged with the DB surface, by `dvergr.sandbox.ns.room`).
   Persistent rooms + forks are behind one surface — same for agents, TUI, web."
  [spindel-ctx & [agent-program-ceiling source-room]]
  (load/require! 'dvergr.discourse)
  (load/require! 'dvergr.rooms)
  (load/require! 'dvergr.room.registry)
  (load/require! 'dvergr.room.store)
  (load/require! 'dvergr.rooms.forks)
  (let [selected-ctx    #(runtime-ctx/selected-context spindel-ctx)
        fork-diff*      @(ns-resolve 'dvergr.rooms.forks 'fork-diff)
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
        archive-room!*  @(ns-resolve 'dvergr.rooms 'archive-room!)
        get-by-slug*    @(ns-resolve 'dvergr.rooms 'get-room-by-slug)
        slug->id*       @(ns-resolve 'dvergr.room.store 'slug->room-id)
        rreg-lookup*    @(ns-resolve 'dvergr.room.registry 'lookup)
        rreg-list*      @(ns-resolve 'dvergr.room.registry 'list-rooms)
        rreg-children*  @(ns-resolve 'dvergr.room.registry 'children)
        registry*       (find-ns 'dvergr.room.registry)
        rstore-ns*      (find-ns 'dvergr.room.store)
        current-source-room (fn []
                              (if (fn? source-room)
                                (source-room)
                                source-room))
        ;; Helpers
        resolve-room    (fn [ref]
                          (binding [rtc/*execution-context* (selected-ctx)]
                            (cond
                              (and (map? ref) (:id ref))
                              (rreg-lookup* (:id ref))            ; canonical Room only
                              (keyword? ref) (rreg-lookup* ref)
                              (string? ref)  (or (rreg-lookup* ref)
                                                 (rreg-lookup* (slug->id* ref))))))
        ;; For ops that act on a Room VALUE the caller may already hold (merge!/
        ;; discard!/participants): resolve refs (slug/id) like fork! does, and
        ;; fall back to a live Room value that is no longer registered (e.g. a
        ;; second, idempotent discard!) rather than rejecting it.
        room-value      (fn [ref]
                          (or (resolve-room ref)
                              (when (and (map? ref) (:ctx ref) (:participants ref))
                                ref)))
        ;; ---------- API ----------
        create-fn   (fn [{:keys [title slug type telegram-chat-id agents
                                 agent-ids parent-id]
                          :as _opts}]
                      (let [world (selected-ctx)]
                        (binding [rtc/*execution-context* world]
                          (let [aset (set (or agents agent-ids))]
                            (create-room!*
                             (cond-> {:title title
                                      :slug  slug
                                      :type  (or type :internal)
                                      :ctx   world}
                               telegram-chat-id (assoc :telegram-chat-id telegram-chat-id)
                               (seq aset)       (assoc :agent-ids aset)
                               parent-id        (assoc :parent-id parent-id)))
                            {:slug slug :title (or title slug) :agents aset
                             :room (rreg-lookup* (slug->id* slug))}))))
        list-fn     (fn [& {:keys [where]}]
                      (binding [rtc/*execution-context* (selected-ctx)]
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
                      (binding [rtc/*execution-context* (selected-ctx)]
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
                      (binding [rtc/*execution-context* (selected-ctx)]
                        (if-let [room (resolve-room ref)]
                          (do
                            ;; An SCI evaluation cannot synchronously join its
                            ;; own controller/context teardown. Self-deletion is
                            ;; a supervisor effect; subordinate Rooms are safe.
                            (let [source-room (current-source-room)]
                              (when (and (= (:id source-room) (:id room))
                                         (= (:incarnation source-room)
                                            (:incarnation room)))
                                (throw (ex-info "A Room cannot delete itself from its own SCI runtime"
                                                {:type ::self-delete
                                                 :room-id (:id room)}))))
                            (let [result (archive-room!* room)]
                              (if (:ok? result)
                                {:deleted (:id room)}
                                (throw (ex-info "Room deletion failed"
                                                {:type ::room-delete-failed
                                                 :room-id (:id room)
                                                 :error (:error result)})))))
                          {:error (str "Room not found: " ref)})))
        ;; The sandbox fork is ISOLATED by default (`:isolation :ctx` — its
        ;; own branched git repo, databases and execution state), which is what
        ;; the documented fork→work→merge!/discard! loop needs. `fork-room`'s
        ;; own default (`:none`, shared ctx) is for message-only probes; an
        ;; agent can still ask for it explicitly — explicit opts win.
        fork-fn     (fn fork-fn
                      ([ref] (fork-fn ref {}))
                      ([ref opts]
                       (binding [rtc/*execution-context* (selected-ctx)]
                         (when-let [room (resolve-room ref)]
                           (binding [rtc/*execution-context* (:ctx room)]
                             (fork-room* room (merge {:isolation :ctx} opts)))))))
        merge-fn    (fn [parent-ref fork-ref]
                      (let [[parent fork] (binding [rtc/*execution-context* (selected-ctx)]
                                            [(room-value parent-ref) (room-value fork-ref)])]
                        (cond
                          (nil? parent) {:error (str "Room not found: " parent-ref)}
                          (nil? fork)   {:error (str "Room not found: " fork-ref)}
                          :else (binding [rtc/*execution-context* (:ctx fork)]
                                  (merge-room* parent fork)))))
        discard-fn  (fn [fork-ref]
                      (if-let [fork (binding [rtc/*execution-context* (selected-ctx)]
                                      (room-value fork-ref))]
                        (binding [rtc/*execution-context* (:ctx fork)]
                          (discard* fork))
                        {:error (str "Room not found: " fork-ref)}))
        ;; Merge review — the per-system diff + tier the agent reads to decide.
        diff-fn     (fn [fork-ref]
                      (binding [rtc/*execution-context* (selected-ctx)]
                        (some-> (resolve-room fork-ref) fork-diff*)))
        review-fn   (fn [fork-ref]
                      (binding [rtc/*execution-context* (selected-ctx)]
                        (some-> (resolve-room fork-ref) fork-review*)))
        forks-fn    (fn []
                      (binding [rtc/*execution-context* (selected-ctx)]
                        (rreg-list* :where #(some? (:forked-from @(:meta %))))))
        participants-fn (fn [ref]
                          (when-let [room (binding [rtc/*execution-context* (selected-ctx)]
                                            (room-value ref))]
                            (vec (keys @(:participants room)))))
        root-fn     (fn []
                      (binding [rtc/*execution-context* (selected-ctx)]
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
        post!        [([ref {:keys [content]}]) "Post a message into a room — how you talk to the people and agents in it. `ref` is a slug or id; the message is a map, e.g. (dvergr.room/post! \"ops\" {:content \"deploy done\"})."]
        messages     [([ref] [ref opts]) "Recent messages in a room, OLDEST first (chronological; the last element is the newest). `opts` takes :limit (default 100 — the most recent n) and :since (a java.util.Date)."]
        children     [([ref]) "Rooms whose parent is this one."]
        set-parent!  [([child parent]) "Re-parent a room, building the room tree."]
        join!        [([ref who]) "Join a room so `who` (an agent id) receives its messages."]
        leave!       [([ref who]) "Stop `who` (an agent id) receiving a room's messages."]
        delete!      [([ref]) "Delete a room: it is closed and archived, its history kept (an operator can unarchive or purge it). On a fork, prefer discard!."]
        fork!        [([ref] [ref opts]) "Branch a room into an ISOLATED copy — its own git repo AND database — so you can experiment freely. This is the safe way to attempt a substantial or risky change: fork, work, then merge! or discard!. Returns the fork Room. `opts` defaults to {:isolation :ctx}; pass {:isolation :none} only for a message-only probe that shares the parent's state."]
        merge!       [([parent fork]) "Collapse a fork's work (git + databases + messages) back into its parent — the other half of the fork→test→merge loop. `parent` and `fork` are each a slug, id or Room. Read `diff`/`review` first to judge it. Returns the PARENT Room, or {:error …} if either room is not found."]
        discard!     [([fork]) "Throw a fork away, keeping the parent untouched. `fork` is a slug, id or Room. Returns the fork Room, or {:error …} if it is not found."]
        diff         [([fork]) "What a fork CHANGED versus its parent — code and data — so you can judge it before merging."]
        review       [([fork]) "The merge-review data for a fork, as {:tier :diff :conflicts}: `:tier` is how mergeable it is (:trivial/:reviewable/:conflict, see `classify`), `:diff` the per-system changes (as `diff`), `:conflicts` concurrent edits with the parent. Pure data — no agent is consulted; nil for a fork that is not isolated."]
        classify     [([diff conflicts]) "How mergeable a fork's diff is (its tier) — used to route it as a task vs a proposal."]
        forks        [([]) "Every fork of this room."]
        participants [([ref]) "Who is in a room (a slug, id or Room) — agents and humans, as a vector of ids; nil if the room is not found."]
        root         [([]) "The root room of the tree. Delegate work through dvergr.agent/hire! so it remains Run-backed and Spindel-composable."]})))
