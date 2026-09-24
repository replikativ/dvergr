(ns dvergr.rooms.repo
  "A room's workspace from, and back to, a Git repository: how a client offloads
   work on its own code. `import!` fills an empty room workspace from a local
   checkout or a remote (https, ssh) with its history; the room's agents then
   work on it in forks, reviewed and merged as usual; `export` returns the
   room's changes since the import as a patch the client applies with
   `git apply`.

   Remote transport is available here, in these host operations, and nowhere
   else: a room's sandbox runs Git without it, so agent code cannot fetch from
   or push to arbitrary URLs with the daemon's credentials."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.catalog.workspace :as ws]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.git.command :as command]
            [geschichte.git.local :as glocal]
            [geschichte.git.transport :as transport]
            [org.replikativ.spindel.engine.core :as ec]))

(def import-tag
  "The tag marking the imported commit: what `export` diffs against."
  "dvergr-import")

(defn- workspace! [room]
  (binding [ec/*execution-context* (:ctx room)]
    (ws/ensure-workspace! room)
    (or (gs/current-workspace)
        (throw (ex-info "Room has no workspace" {:type ::no-workspace :room (:id room)})))))

(defn- git
  "Run Git `argv` in `room`'s workspace; with `remote?`, with network transport."
  [room argv & {:keys [remote?]}]
  (binding [ec/*execution-context* (:ctx room)]
    (let [{:keys [conn]} (gs/current-workspace)]
      (command/execute (cond-> {:conn conn :root "/" :config (atom {})
                                :repo-relative #(let [p (str %)]
                                                  (if (contains? #{"." "/"} p) "" (str/replace p #"^/+" "")))}
                         remote? (assoc :remote-ops transport/operations))
                       (vec argv)))))

(defn- git! [room argv & opts]
  (let [{:keys [exit stdout stderr] :as r} (apply git room argv opts)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (first argv) " failed: " (str/trim (str stderr)))
                      {:type ::git-failed :argv argv :exit exit})))
    (assoc r :stdout (str stdout))))

(defn- head [room]
  (let [{:keys [exit stdout]} (git room ["rev-parse" "HEAD"])]
    (when (zero? exit) (str/trim (str stdout)))))

(defn- local-source [source]
  (let [path (str/replace (str source) #"^file://" "")
        f (io/file path)]
    (when (.isDirectory f) (.getCanonicalPath f))))

(defn import!
  "Fill `room`'s empty workspace from `source`: a local Git checkout (its path,
   or a file:// URL) or a remote URL (https, ssh). A local import takes the
   checkout's current branch, or `:branch`, with its committed history
   (uncommitted changes in the checkout are not imported); a remote one takes
   the remote's default branch. Tags the imported commit `import-tag`. Returns
   `{:source :commit :files n}`."
  [room source & {:keys [branch]}]
  (let [{:keys [conn]} (workspace! room)]
    (when (head room)
      (throw (ex-info "The room's workspace already has history; import into a new room"
                      {:type ::not-empty :room (:id room)})))
    (if-let [path (local-source source)]
      (binding [ec/*execution-context* (:ctx room)]
        (glocal/import! conn path (cond-> {:clone? true} branch (assoc :branch branch))))
      (do
        (when branch
          (throw (ex-info "A remote import takes the remote's default branch"
                          {:type ::remote-branch-unsupported :branch branch})))
        (when-not (re-find #"^(https?://|ssh://|[\w.-]+@[\w.-]+:)" (str source))
          (throw (ex-info (str "Not a local Git checkout or a remote URL: " source)
                          {:type ::unknown-source :source source})))
        (git! room ["remote" "add" "origin" (str source)])
        (git! room ["pull" "origin"] :remote? true)))
    (let [commit (or (head room)
                     (throw (ex-info "The source has no commit to import"
                                     {:type ::empty-source :source source})))]
      (git! room ["tag" import-tag])
      {:source (str source)
       :commit commit
       :files (count (str/split-lines (:stdout (git! room ["ls-files"]))))})))

(defn export
  "The room's changes since its import, as a patch: commits the workspace's
   pending work first (a merge adopts commits, and so does this), then diffs
   the imported commit against the room's head. Apply it in the source
   checkout with `git apply`. Returns `{:base :head :patch :stat :files}`."
  [room]
  (workspace! room)
  (let [base (:exit (git room ["rev-parse" import-tag]))]
    (when-not (zero? base)
      (throw (ex-info "The room was not imported from a repository (no import tag)"
                      {:type ::not-imported :room (:id room)}))))
  (forks/commit-workspace! room "Changes for export")
  (let [patch (:stdout (git! room ["diff" import-tag "HEAD"]))
        names (->> (str/split-lines (:stdout (git! room ["diff" "--name-status" import-tag "HEAD"])))
                   (remove str/blank?)
                   (mapv (fn [line] (let [[status path] (str/split line #"\s+" 2)]
                                      {:status status :path path}))))]
    {:base (str/trim (:stdout (git! room ["rev-parse" import-tag])))
     :head (head room)
     :patch patch
     :stat (:stdout (git! room ["diff" "--stat" import-tag "HEAD"]))
     :files names}))
