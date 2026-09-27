(ns dvergr.authority
  "The authorization seam for sandbox effects: one `can?` predicate, shaped like
   simmis's `is.simm.model.access/can?` (a subject, an action, a resource) so
   eacl can later answer behind the same signature.

   `can?` is a pure function of a relations VALUE (`relations` takes it from
   the room registry), which keeps it testable and lets the same decision be
   taken against a snapshot.

   Actions, weakest first (as in simmis): `:read` see it, `:write` change it or
   write a fork of it, `:merge` land a fork onto it, `:admin` discard or delete
   it. The policy for an agent (doc/effects.md, decisions): it acts on its own
   room and the rooms beneath it (forks and nested rooms, transitively), and on
   rooms it participates in. On its own tree it may read, write and merge, and
   discard or delete what is beneath its room (never its room itself); where it
   only participates it may read and write. Anything else needs a grant, which
   this seam does not know yet: deny by default."
  (:require [clojure.string :as str]))

(defn relations
  "The relations `can?` reads, from room maps (`dvergr.room.registry/list-rooms`):
   `{:parent {id parent-id} :participants {id #{agent-id}} :slug {slug id}}`."
  [rooms]
  (reduce (fn [acc {:keys [id slug parent-id participants]}]
            (cond-> (assoc-in acc [:participants id]
                              (set (some-> participants deref keys)))
              parent-id (assoc-in [:parent id] parent-id)
              slug (assoc-in [:slug (str slug)] id)))
          {:parent {} :participants {} :slug {}}
          rooms))

(defn resolve-room
  "A room reference (an id keyword, its string, or a slug) to its id in
   `relations`, or nil."
  [{:keys [participants slug]} ref]
  (let [s (str/replace (str ref) #"^:" "")
        k (keyword s)]
    (cond
      (contains? participants k) k
      (contains? slug s) (get slug s))))

(defn- ancestors-of [{:keys [parent]} id]
  (take-while some? (rest (iterate parent id))))

(defn- beneath?
  "Is `room` `home` or beneath it (a fork or nested room, transitively)?"
  [rel home room]
  (or (= room home) (some #{home} (ancestors-of rel room))))

(defn can?
  "May `subject` (`{:agent id :room home-id}`) perform `action` on the room
   `resource` (`{:room ref}`)? Deny by default: an unknown room, action or
   subject is a denial."
  [rel {:keys [agent room]} action {ref :room}]
  (let [home room
        r (resolve-room rel ref)
        own? (and r home (beneath? rel home r))
        member? (and r agent (contains? (get-in rel [:participants r]) agent))]
    (boolean
     (when r
       (case action
         (:read :write) (or own? member?)
         :merge own?
         :admin (and own? (not= r home))
         false)))))

(def room-actions
  "Each room effect's checks: the action on each room its resource names."
  {:room/read    [[:read :room]]
   :room/post    [[:write :room]]
   :room/join    [[:write :room]]
   ;; re-parenting moves a room into another's tree: it needs authority over
   ;; the room itself, or re-parenting would be a way to gain it
   :room/write   [[:admin :room] [:write :parent]]
   :room/fork    [[:write :room]]
   :room/merge   [[:merge :room] [:read :fork]]
   :room/discard [[:admin :room]]
   :room/delete  [[:admin :room]]})

(defn decide
  "The checks `effect` fails, as `[action ref]`, for `subject` over `rel`
   (empty ⇒ allowed). Effects without a room resource are not decided here."
  [rel subject {kind :effect resource :resource}]
  (vec (for [[action k] (get room-actions kind)
             :let [ref (get resource k)]
             :when (and ref (not (can? rel subject action {:room ref})))]
         [action ref])))
