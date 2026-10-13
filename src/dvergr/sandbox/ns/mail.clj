(ns dvergr.sandbox.ns.mail
  "Mount the room's attached mailbox datahike conn for the SCI sandbox.
   `(dvergr.mail/inbox)` resolves it LIVE through `ygg/system` under the bound ctx
   (fork-aware + sees a mailbox attached after this ctx was built); `*inbox*` is a
   snapshot taken at mount time (kept for the seed's existing usage). nil when no
   mailbox is attached. Read helpers (recent/search/unread) are agent-readable
   stdlib SOURCE in dvergr/mail/inbox.clj."
  (:require [sci.core :as sci]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.sandbox.ns.datahike :as ns-datahike]
            [dvergr.sandbox.ns.doc :as doc]))

(defn add-mail-ns!
  "Mount dvergr.mail into the SCI ctx. `inbox` is a 0-arg LIVE resolver — call it
   per-access so a fork / a later-attached mailbox is reflected; `*inbox*` is the
   mount-time snapshot resolved under the bound (room/fork) ctx. Both are
   connection handles (`dvergr.sandbox.ns.datahike/world-connection`): the
   mirrored Datahike API takes them, the connection stays with the host."
  [sci-ctx]
  (let [handle (fn [conn] (when conn (ns-datahike/world-connection (constantly conn))))]
    (sci/add-namespace! sci-ctx 'dvergr.mail
                        (doc/with-docs
                          {'inbox   (fn [] (handle (some-> (ygg/system "mail") :conn)))
                           '*inbox* (handle (some-> (ygg/system "mail") :conn))}
                          ;; `*inbox*` is a conn (or nil) — a value with no
                          ;; signature to state, so it carries no entry here; the
                          ;; ns-guide overview describes it.
                          '{inbox [([]) "The datahike conn for YOUR room's attached mailbox, resolved LIVE — call it per access so a fork, or a mailbox attached after this sandbox was built, is reflected. nil when no mailbox is attached. Read helpers (recent/search/unread) are readable source: (require '[dvergr.mail.inbox])."
                                   [:=> :cat :any]]})))
  sci-ctx)
