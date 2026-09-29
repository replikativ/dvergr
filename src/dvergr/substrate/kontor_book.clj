(ns dvergr.substrate.kontor-book
  "A room's business book: a kontor datahike store that settles by replay
   (kontor ADR-172), never by datom merge.

   Registered as a yggdrasil system with spindel's settlement hooks, so
   every fork of the room carries an autonomous book — an agent books,
   numbers and reverses in it as in the root — and when the parent takes the
   fork in (a merge, a copy family, a checkpoint), `kontor.settlement`
   extracts what the fork booked since its base, checks it against what the
   parent claimed meanwhile, and re-posts it through kontor's gate in the
   parent. Review reads the same intents (`ygg/context-diff`) and conflicts
   (`ygg/context-conflicts`).

   The three dbs a hook needs come from yggdrasil: the fork's and the
   parent's current values, and the base — the common ancestor of their
   heads."
  (:require [datahike.api :as d]
            [kontor.core :as kontor]
            [kontor.settlement :as settlement]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [yggdrasil.protocols :as yp]))

(defn- system-in [ctx system-id]
  (binding [ec/*execution-context* ctx] (ygg/system system-id)))

(defn- dbs
  "{:base :world :parent} dbs of a hook's arguments."
  [{:keys [system-id child-ctx parent-ctx]}]
  (let [world (system-in child-ctx system-id)
        parent (system-in parent-ctx system-id)
        base (yp/common-ancestor world (yp/snapshot-id world) (yp/snapshot-id parent))]
    (when-not base
      (throw (ex-info "The fork's book has no common ancestor with its parent's: its base is gone (garbage collected?)"
                      {:type ::no-base :system system-id})))
    {:base (yp/as-of world base)
     :world (d/db (:conn world))
     :parent (d/db (:conn parent))}))

(defn- stamp! [{:keys [system-id parent-ctx contributions]}]
  (settlement/stamp!
   (:conn (system-in parent-ctx system-id))
   (->> contributions
        (mapcat :intents)
        ;; the same intent reached through two members is one
        (reduce (fn [acc i] (if (some #(= (:intent/id i) (:intent/id %)) acc) acc (conj acc i))) [])
        (sort-by (juxt :effective-date #(str (:intent/id %)))))))

(def settlement-policy
  "The yggdrasil registration options of a book."
  {:grade :linear
   :intents (fn [args] (let [{:keys [base world]} (dbs args)] (settlement/extract base world)))
   :parent-footprint (fn [args] (let [{:keys [base parent]} (dbs args)] (settlement/parent-claims base parent)))
   :stamp stamp!})

(defn register-book!
  "Register the kontor book on `conn` as yggdrasil system `system-name` in the
   bound execution context, settling by replay. Installs kontor's schema
   (idempotent). Returns the YggRef."
  [conn system-name]
  (kontor/install-schema! conn)
  (ygg/register! (dh-adapter/create conn {:system-name system-name}) settlement-policy))
