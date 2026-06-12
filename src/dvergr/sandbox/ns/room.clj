(ns dvergr.sandbox.ns.room
  "The agent's view of ITS room as a database-backed project — mounted in SCI as
   `dvergr.room`. Holds the room's own datahike connections (fork-aware) + a small
   library of the common queries that are otherwise fiddly to write by hand.

   `*room*` — the room's OWN datahike (the messages store: conversation, schedules,
              and a place to transact room state). `*kb*` — the room's knowledge
              base (`:entity/*` katzen ACSet). Both are resolved per `[room,agent]`
              context, so a FORK's sandbox transparently gets the branched conns and
              everything the agent writes here forks/merges/discards with the room.
              NEITHER is system-db — sandbox data never touches the global registry.

   Other databases (attached KBs, ones the agent creates) are reached through the
   faithful `datahike.api` namespace; the agent can `(require …)`-style write its
   own helper namespaces over them, exactly like a normal Clojure project."
  (:require [datahike.api :as d]
            [dvergr.system.rooms :as srooms]
            [dvergr.sandbox.ns.kb :as ns-kb]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [sci.core :as sci]))

(defn- safe [f] (try (f) (catch Throwable t {:error (.getMessage t)})))

(defn add-room-ns!
  "Mount the ONE `dvergr.room` namespace the agent sees: the Room OPS (create!/
   fork!/merge!/post!/messages/… via `ns-kb/room-ops-map`) MERGED with the room's
   DB surface — `*room*`/`*kb*` (fork-aware conns), `databases`/`db` (discovery),
   and the common-query helpers. Also mounted under the legacy alias `room` for
   back-compat with existing agent profiles. `room-conn` = the room's messages
   store; `kb-conn` = its knowledge base. Any of `room-conn`/`kb-conn`/`room-id`
   may be nil (room-less ctx) — the helpers degrade gracefully."
  [sci-ctx room-conn kb-conn room-id ctx]
  (let [kb-find        (fn [title]
                         (safe #(when kb-conn
                                  (d/q '[:find (pull ?e [*]) . :in $ ?t
                                         :where [?e :entity/title ?t]] @kb-conn title))))
        kb-by-type     (fn [t]
                         (safe #(when kb-conn
                                  (d/q '[:find [(pull ?e [:entity/title :entity/summary :entity/type]) ...]
                                         :in $ ?ty :where [?e :entity/type ?ty]]
                                       @kb-conn (keyword t)))))
        kb-search      (fn [term]
                         (safe #(when kb-conn
                                  (let [lc (str/lower-case (str term))]
                                    (->> (d/q '[:find [(pull ?e [:entity/title :entity/summary]) ...]
                                                :where [?e :entity/title _]] @kb-conn)
                                         (filter (fn [e] (str/includes?
                                                          (str/lower-case (str (:entity/title e) " " (:entity/summary e)))
                                                          lc)))
                                         (take 25) vec)))))
        msg-time       (fn [m] (or (some-> ^java.util.Date (:message/created-at m) .getTime) 0))
        recent-msgs    (fn [n]
                         (safe #(when room-conn
                                  (->> (d/q '[:find [(pull ?m [:message/content :message/role :message/created-at]) ...]
                                              :where [?m :message/content _]] @room-conn)
                                       (sort-by msg-time >)
                                       (take (or n 20)) vec))))
        search-msgs    (fn [term]
                         (safe #(when room-conn
                                  (let [lc (str/lower-case (str term))]
                                    (->> (d/q '[:find [(pull ?m [:message/content :message/role :message/created-at]) ...]
                                                :where [?m :message/content _]] @room-conn)
                                         (filter (fn [m] (str/includes? (str/lower-case (str (:message/content m))) lc)))
                                         (take 50) vec)))))
        schedules      (fn []
                         (safe #(when room-conn
                                  (d/q '[:find [(pull ?s [*]) ...]
                                         :where [?s :schedule/id _]] @room-conn))))
        ;; Discovery: WHERE the agent's databases are + by-name access. Resolved
        ;; fork-aware under `ctx` so a fork lists/returns its branched systems.
        databases      (fn [] (safe #(when room-id
                                       (binding [ec/*execution-context* ctx]
                                         (srooms/room-databases room-id)))))
        db             (fn [db-name] (safe #(when room-id
                                              (binding [ec/*execution-context* ctx]
                                                (srooms/room-conn-by-name room-id db-name)))))
        ;; Reclaim unreachable storage for THIS room/fork's workspace (datahike
        ;; index blobs + git objects). Default keeps all history (orphan garbage
        ;; only); pass {:remove-before <Date>} to collapse old history.
        gc!            (fn gc!
                         ([] (gc! {}))
                         ([opts] (safe #(binding [ec/*execution-context* ctx] (ygg/gc! opts)))))
        room-map (merge (ns-kb/room-ops-map ctx)        ; create!/fork!/merge!/post!/messages/…
                        {'*room*          room-conn      ; the room's own datahike (messages store)
                         '*kb*            kb-conn         ; the room's knowledge base
                         'databases       databases       ; [{:name :type :permission}] — your DBs
                         'db              db              ; fork-aware conn to one by name
                         'kb-find         kb-find
                         'kb-by-type      kb-by-type
                         'kb-search       kb-search
                         'recent-messages recent-msgs
                         'search-messages search-msgs
                         'schedules       schedules
                         'gc!             gc!})]
    (sci/add-namespace! sci-ctx 'dvergr.room room-map)))
