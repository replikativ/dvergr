(ns dvergr.system.mail
  "Attach a briefkasten mailbox to a room's execution context as a yggdrasil
   datahike system, so the room's sandbox reads it (fork-aware) via the mounted
   `dvergr.mail/*inbox*`. A forked agent's triage runs on a CoW copy — the real
   inbox is untouched until merged.

   IMAP sync stays daemon-side (`sync!`); sending is NOT implemented (briefkasten
   has no SMTP — a postal-based send can come later). briefkasten + clojure-mail
   are OPTIONAL deps (the :cli/:tui/:dev aliases), so everything here is lazy via
   requiring-resolve — this namespace loads fine without them."
  (:require [yggdrasil.adapters.datahike :as dh-adapter]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.substrate.config :as config]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent Executors TimeUnit]))

(def mail-system-name "mail")
(def ^:private accounts (atom {})) ; account-id → briefkasten handle (cached)

(defn available?
  "True when briefkasten is on the classpath."
  []
  (try (require 'org.replikativ.briefkasten.core) true (catch Throwable _ false)))

(defn open-account!
  "Open + cache the briefkasten account for `account-id` (from config.local.edn
   `:mail`). Opening does NOT contact IMAP. Returns the handle, or nil if
   briefkasten or the config is absent."
  [account-id]
  (or (get @accounts account-id)
      (when (available?)
        (when-let [cfg (config/mail-account account-id)]
          (let [create! (requiring-resolve 'org.replikativ.briefkasten.core/create-account!)
                acct    (create! (assoc cfg :id account-id))]
            (swap! accounts assoc account-id acct)
            acct)))))

(defn attach!
  "Register `account-id`'s mailbox datahike conn as the `mail` system in the
   CURRENT (room) execution context — the context MUST be bound. Mirrors how
   rooms register their kb/msgs/repo. Returns the account handle, or nil."
  [account-id]
  (when-let [acct (open-account! account-id)]
    (ygg/register! (dh-adapter/create (:conn acct) {:system-name mail-system-name}))
    (tel/log! {:id :mail/attached :data {:account account-id}}
              "Mailbox attached to room execution context")
    acct))

(defn sync!
  "Daemon-side IMAP pull (briefkasten `sync!`) for `account-id`. Never exposed to
   agents — this is the only IMAP touch-point, and it's READ-ONLY (briefkasten opens
   folders READ_ONLY for sync). Local datahike changes (fork triage, merges) never
   propagate to the server; pushing flags back would be a separate explicit step.
   Returns nil if mail isn't available."
  [account-id & {:keys [folders] :or {folders ["INBOX"]}}]
  (when-let [acct (open-account! account-id)]
    ((requiring-resolve 'org.replikativ.briefkasten.core/sync!) acct :folders folders)))

;; ---------------------------------------------------------------------------
;; Daemon integration: attach to a room + a scheduled background pull
;; ---------------------------------------------------------------------------

(defonce ^:private sync-pool (atom nil))

(defn start-sync!
  "Start a daemon background thread that runs `sync!` for `account-id` every
   `interval-ms` (fixed delay; first run after one interval). Idempotent — replaces
   any prior loop. Errors are logged, never fatal."
  [account-id interval-ms]
  (when @sync-pool (.shutdownNow ^java.util.concurrent.ExecutorService @sync-pool))
  (let [pool (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleWithFixedDelay pool
                             (fn [] (try (sync! account-id)
                                         (catch Throwable e
                                           (tel/log! {:level :warn :id :mail/sync-failed
                                                      :data {:account account-id :error (.getMessage e)}}
                                                     "Mail IMAP sync failed"))))
                             interval-ms interval-ms TimeUnit/MILLISECONDS)
    (reset! sync-pool pool)))

(defn stop-sync!
  "Stop the background sync loop."
  []
  (when-let [pool @sync-pool] (.shutdownNow ^java.util.concurrent.ExecutorService pool))
  (reset! sync-pool nil))

(defn start-integration!
  "Daemon hook (local-only, no write-back): attach `account-id`'s mailbox to
   `room-ctx` (a room's execution context) and start the scheduled READ-ONLY IMAP
   pull. No-op when briefkasten or the account config is absent. Returns true on
   attach."
  [{:keys [account-id room-ctx interval-ms] :or {interval-ms 600000}}]
  (when (and (available?) room-ctx (config/mail-account account-id))
    (binding [ec/*execution-context* room-ctx]
      (when (attach! account-id)
        (start-sync! account-id interval-ms)
        (tel/log! {:id :mail/integration-started
                   :data {:account account-id :interval-ms interval-ms}}
                  "Mail integration started (attach + scheduled pull)")
        true))))
