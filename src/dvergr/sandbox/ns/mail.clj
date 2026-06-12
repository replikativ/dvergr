(ns dvergr.sandbox.ns.mail
  "Mount the room's attached mailbox datahike conn for the SCI sandbox.
   `(dvergr.mail/inbox)` resolves it LIVE through `ygg/system` under the bound ctx
   (fork-aware + sees a mailbox attached after this ctx was built); `*inbox*` is a
   snapshot taken at mount time (kept for the seed's existing usage). nil when no
   mailbox is attached. Read helpers (recent/search/unread) are agent-readable
   stdlib SOURCE in dvergr/mail/inbox.clj."
  (:require [sci.core :as sci]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn add-mail-ns!
  "Mount dvergr.mail into the SCI ctx. `inbox` is a 0-arg LIVE resolver — call it
   per-access so a fork / a later-attached mailbox is reflected; `*inbox*` is the
   mount-time snapshot resolved under the bound (room/fork) ctx."
  [sci-ctx]
  (sci/add-namespace! sci-ctx 'dvergr.mail
                      {'inbox   (fn [] (some-> (ygg/system "mail") :conn))
                       '*inbox* (some-> (ygg/system "mail") :conn)})
  sci-ctx)
