(ns dvergr.sandbox.ns.mail
  "Mount the room's attached mailbox datahike conn for the SCI sandbox.
   `(dvergr.mail/inbox)` resolves it LIVE through `ygg/system` under the bound ctx
   (fork-aware + sees a mailbox attached after this ctx was built); `*inbox*` is the
   same handle, set when a mailbox was attached at mount (kept for the seed's
   existing usage). nil when no mailbox is attached. Read helpers (recent/search/unread) are agent-readable
   stdlib SOURCE in dvergr/mail/inbox.clj."
  (:require [sci.core :as sci]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.sandbox.ns.datahike :as ns-datahike]
            [dvergr.sandbox.ns.doc :as doc]))

(defn add-mail-ns!
  "Mount dvergr.mail into the SCI ctx. `inbox` is a 0-arg LIVE resolver — call it
   per-access so a mailbox attached later is reflected; `*inbox*` is set when a
   mailbox was attached at mount. Both are connection handles
   (`dvergr.sandbox.ns.datahike/world-connection`) that resolve the mailbox in
   the selected world at each use, so one kept across a fork reaches the fork's
   mailbox. The mirrored Datahike API takes them; the connection stays with the
   host."
  [sci-ctx]
  (let [;; resolved in the selected world at each use: a handle kept across a
        ;; fork reaches the fork's mailbox, never the parent's
        mailbox-conn #(some-> (ygg/system "mail") :conn)
        handle (fn [] (when (mailbox-conn) (ns-datahike/world-connection mailbox-conn)))]
    (sci/add-namespace! sci-ctx 'dvergr.mail
                        (doc/with-docs
                          {'inbox   handle
                           '*inbox* (handle)}
                          ;; `*inbox*` is a conn (or nil) — a value with no
                          ;; signature to state, so it carries no entry here; the
                          ;; ns-guide overview describes it.
                          '{inbox [([]) "The datahike conn for YOUR room's attached mailbox, resolved LIVE — call it per access so a fork, or a mailbox attached after this sandbox was built, is reflected. nil when no mailbox is attached. Read helpers (recent/search/unread) are readable source: (require '[dvergr.mail.inbox])."
                                   [:=> :cat :any]]})))
  sci-ctx)
