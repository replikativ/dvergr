(ns dvergr.drive.integration
  "Wire the room drive (dvergr.drive.*) into a running host: mount each room's
   drive at /drive in the agent shell, and configure the blob store from the
   host's config. A host calls `install!` once at startup (the full daemon does;
   simmis / custom hosts call the same fn)."
  (:require [dvergr.drive.core :as drive]
            [dvergr.drive.fs :as dfs]
            [dvergr.drive.blobs :as blobs]
            [dvergr.intake.bash :as bash]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

(defn store-upload!
  "Store an uploaded file into `room`'s drive under `subdir`, returning
   {:note \"/drive/<subdir>/<name>\" :attachment {:node-id … :blob-id … :mime …}} (the shape
   channel handlers expect), or nil. Channel-agnostic — Telegram, the web upload
   route, mail, etc. all call this; only resolving the target Room is
   channel-specific. `room` is a discourse Room. Binds the room's ctx."
  [room subdir {:keys [bytes file-name mime photo? source]}]
  (when (and room (seq bytes))
    (when-let [rid (some-> (sdb/room-by-slug (:slug room)) :room/id)]
      (binding [ec/*execution-context* (:ctx room)]
        (let [name (or file-name
                       (str (if photo? "photo-" "file-") (subs (blobs/sha256-hex bytes) 0 8)))
              node (drive/store-in-room! rid [subdir] name bytes
                                         :mime mime
                                         :source (or source (if photo? :photo :upload)))]
          {:note (str "/drive/" subdir "/" (:fs.node/name node))
           :attachment {:node-id (str (:fs.node/id node))
                        ;; CAS id — hosts serve playback/download by blob hash
                        :blob-id (:fs.node/blob node)
                        :mime mime}})))))

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
  (when-let [f (:drive-conn-fn config)]
    (drive/set-conn-resolver! f))
  (install-mounts!)
  (tel/log! {:id ::drive-installed
             :data {:blob-store (get-in config [:blob-store :backend] :file)}}
            "Room drive installed (/drive mount + blob store)"))
