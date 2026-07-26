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
  (let [;; EVERY readable KB, not just `*kb*`. A room's knowledge is spread over
        ;; its own KB plus whatever is granted to it, and the two are written
        ;; through different bindings of the same katzen schema — dvergr's
        ;; `:entity/title` and a product's `:S.Page/title`. Reading one store
        ;; through one binding is why an agent could write knowledge and then
        ;; fail to find it: it was searching a different store.
        ;;
        ;; Results carry `:kb` so the agent knows WHERE a hit lives and can
        ;; address that KB by name (see `kbs` / `kb`).
        title-attrs    [:entity/title :S.Page/title]
        readable       (fn []
                         (if room-id
                           (binding [ec/*execution-context* ctx]
                             (srooms/room-kb-conns room-id))
                           (when kb-conn [{:conn kb-conn :slug "kb"}])))
        titled         (fn [db]
                         ;; [{:title … :summary …}] across whichever binding the
                         ;; store actually uses; a store carrying both yields both.
                         (into []
                               (mapcat (fn [a]
                                         (map (fn [[t summ]] {:title t :summary summ})
                                              (d/q '[:find ?t ?summ :in $ ?a
                                                     :where [?e ?a ?t]
                                                     [(get-else $ ?e :entity/summary "") ?summ]]
                                                   db a))))
                               title-attrs))
        each-kb        (fn [f]
                         (into [] (mapcat (fn [{:keys [conn slug]}]
                                            (map #(assoc % :kb slug) (f @conn))))
                               (readable)))
        kb-find        (fn [title]
                         (safe #(first (each-kb (fn [db]
                                                  (filter (comp #{title} :title)
                                                          (titled db)))))))
        kb-by-type     (fn [t]
                         (safe #(each-kb (fn [db]
                                           (d/q '[:find [(pull ?e [:entity/title :entity/summary :entity/type]) ...]
                                                  :in $ ?ty :where [?e :entity/type ?ty]]
                                                db (keyword t))))))
        kb-search      (fn [term]
                         (safe #(let [lc (str/lower-case (str term))]
                                  (->> (each-kb titled)
                                       (filter (fn [e] (str/includes?
                                                        (str/lower-case (str (:title e) " " (:summary e)))
                                                        lc)))
                                       (take 25) vec))))
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
        ;; Which KBs this room may WRITE, and which one `*kb*` is. A room's own
        ;; KB is the default, but a room whose knowledge lives in an ATTACHED KB
        ;; would otherwise write into its own empty one with nothing saying so —
        ;; the agent could not even name the alternatives. These make the choice
        ;; visible and addressable.
        kbs (fn [] (safe #(binding [ec/*execution-context* ctx]
                            (let [ws (srooms/writable-kbs room-id)]
                              (into [] (map-indexed
                                        (fn [i {:keys [slug permission]}]
                                          {:name slug :permission permission
                                           :default? (zero? i)}))
                                    ws)))))
        kb  (fn [slug] (safe #(binding [ec/*execution-context* ctx]
                                (srooms/room-kb-conn room-id slug))))
        room-map (merge (ns-kb/room-ops-map ctx)        ; create!/fork!/merge!/post!/messages/…
                        {'*room*          room-conn      ; the room's own datahike (messages store)
                         '*kb*            kb-conn         ; the DEFAULT writable KB (see kbs)
                         'kbs             kbs             ; [{:name :permission :default?}] — writable KBs
                         'kb              kb              ; fork-aware conn to a writable KB by name
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
