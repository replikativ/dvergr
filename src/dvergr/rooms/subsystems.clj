(ns dvergr.rooms.subsystems
  "One entrypoint a host calls to bring up every per-room subsystem — so a custom
   host (simmis, an embed) can't silently miss one and leave a subsystem dormant.

   Room-level subsystems attach to each room automatically via register-hooks
   (the scheduler spin, the message-fold). Those hooks install simply by LOADING
   the nses that self-register — which this ns does in its `:require`, so
   requiring `dvergr.rooms.subsystems` is enough to arm them. `start-room-subsystems!`
   then starts the daemon clock (drives scheduler firing) and installs the /drive
   mount + blob store.

   The full daemon calls this; a custom host calls the same fn instead of
   re-deriving the wiring by hand."
  (:require [dvergr.rooms.scheduler]    ; installs the ::scheduler register-hook
            [dvergr.rooms.messages]     ; installs the ::eager-signal / ::drop-room hooks
            [dvergr.runtime.clock :as clock]
            [dvergr.drive.integration :as drive-integration]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

(defn start-room-subsystems!
  "Bring up per-room subsystems once (idempotent). Room subsystems (scheduler,
   message-fold) auto-attach on room register via the hooks armed by loading this
   ns; here we install the /drive mount + blob store and start the reactive clock
   that drives all time-based reactivity. `config` is the host config
   (`:blob-store` optional). `exec-ctx` is the root execution context."
  [exec-ctx config]
  (drive-integration/install! config)
  (binding [ec/*execution-context* exec-ctx]
    (clock/start!))
  (tel/log! {:id ::room-subsystems-started} "Room subsystems started (drive + clock)"))
