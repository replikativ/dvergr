(ns dvergr.drive.integration
  "Wire the room drive (dvergr.drive.*) into a running host: mount each room's
   drive at /drive in the agent shell, and configure the blob store from the
   host's config. A host calls `install!` once at startup (the full daemon does;
   simmis / custom hosts call the same fn)."
  (:require [dvergr.drive.core :as drive]
            [dvergr.drive.fs :as dfs]
            [dvergr.drive.blobs :as blobs]
            [dvergr.intake.bash :as bash]
            [taoensso.telemere :as tel]))

(defn install-mounts!
  "Install the bash mounts-fn so every room's shell sees its drive at /drive
   (plain ls/cat/grep + the media fns). The drive is provisioned lazily on the
   room's first shell use (idempotent). Errors degrade to no mount, never break
   the shell."
  []
  (bash/set-mounts-fn!
   (fn [chat-ctx]
     (when-let [rid (:room-id chat-ctx)]
       (try {"/drive" (dfs/make (drive/ensure-room-drive! rid))}
            (catch Throwable t
              (tel/log! {:level :warn :id ::drive-mount-failed
                         :data {:room-id rid :error (.getMessage t)}})
              nil))))))

(defn install!
  "Configure the blob store from `config` (`:blob-store` = a konserve store
   config; filestore default when absent) and install the /drive mount. Idempotent."
  [config]
  (when-let [bs (:blob-store config)]
    (blobs/set-store-config! bs))
  (install-mounts!)
  (tel/log! {:id ::drive-installed
             :data {:blob-store (get-in config [:blob-store :backend] :file)}}
            "Room drive installed (/drive mount + blob store)"))
