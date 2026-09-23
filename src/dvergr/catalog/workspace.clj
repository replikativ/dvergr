(ns dvergr.catalog.workspace
  "A room's files, for catalog workflows and their checkers: read a tree, seed
   files (committed), and give a benchmark world a workspace of its own.

   A room's workspace is a Geschichte (virtual git) repository registered as a
   Yggdrasil system in the room's context (`dvergr.substrate.geschichte`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.repo :as repo]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy]))

(def ^:private max-files 500)
(def ^:private max-bytes (* 2 1024 1024))

(defn room-fs
  "`room`'s workspace filesystem, or nil when it has none."
  [room]
  (binding [ec/*execution-context* (:ctx room)]
    (gs/filesystem)))

(defn read-tree
  "`{path text}` of the files under `dir` in `room`'s workspace (recursive,
   bounded by file count and total size); `{}` without a workspace."
  [room dir]
  (if-let [fs (room-fs room)]
    (let [total (atom 0)]
      (letfn [(walk [path acc]
                (reduce (fn [acc {:keys [type] entry-path :path}]
                          (cond
                            (<= max-files (count acc)) (reduced acc)
                            (= :dir type) (walk entry-path acc)
                            (= :file type)
                            (let [text (str (mfs/read-file fs entry-path))]
                              (if (< max-bytes (swap! total + (count text)))
                                (reduced acc)
                                (assoc acc entry-path text)))
                            :else acc))
                        acc
                        (try (mfs/list-dir fs path) (catch Throwable _ nil))))]
        (if (mfs/exists? fs dir) (walk dir {}) {})))
    {}))

(defn seed!
  "Write `files` (`{path text}`, absolute workspace paths) into `room`'s
   workspace and commit them: a merge into a dirty workspace is refused."
  [room files]
  (let [fs (or (room-fs room)
               (throw (ex-info "Room has no workspace" {:type ::no-workspace :room (:id room)})))]
    (doseq [[path text] (sort files)]
      (let [dirs (->> (str/split path #"/") (remove str/blank?) butlast
                      (reductions #(str %1 "/" %2) "") rest)]
        (doseq [dir dirs]
          (when-not (mfs/exists? fs dir) (mfs/mkdir fs dir))))
      (when-not (mfs/write-string! fs path text false)
        (throw (ex-info (str "Could not write " path) {:type ::seed-failed :path path}))))
    (forks/commit-workspace! room (str "Seed " (count files) " files"))
    (count files)))

(defn open-memory-workspace!
  "Give `room` (a benchmark attempt's world) a fresh in-memory workspace,
   registered in its context, where a room store has none. The repository is
   not deleted afterwards: the evaluator's capture reads it after the Run's
   cleanups, so a cleanup cannot release it; it is the size of the fixtures."
  [room]
  (let [cfg (assoc (gs/repository-config (str "/memory/" (random-uuid)))
                   :store {:backend :memory :id (random-uuid)})]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (repo/init! conn {:name "attempt workspace"})
      (binding [ec/*execution-context* (:ctx room)]
        (ygg/register! (gy/create conn {:system-name (str "room-repo-attempt-" (random-uuid))})))
      conn)))
