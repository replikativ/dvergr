(ns dvergr.catalog.workspace
  "A room's files, for catalog workflows and their checkers: read a tree, seed
   files (committed), and give a benchmark world a workspace of its own.

   A room's workspace is a Geschichte (virtual git) repository registered as a
   Yggdrasil system in the room's context (`dvergr.substrate.geschichte`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.blob :as blob]
            [dvergr.rooms.forks :as forks]
            [dvergr.substrate.geschichte :as gs]
            [geschichte.repo :as repo]
            [geschichte.query :as query]
            [muschel.fs :as mfs]
            [muschel.fs.geschichte :as primary-fs]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.geschichte :as gy])
  (:import [java.nio CharBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]))

(def ^:private max-files 500)
(def ^:private max-bytes (* 2 1024 1024))

(defn room-fs
  "`room`'s workspace filesystem, or nil when it has none."
  [room]
  (binding [ec/*execution-context* (:ctx room)]
    (gs/filesystem)))

(defn read-tree
  "`{path text}` of the files under `dir` in `room`'s workspace (recursive,
   bounded by file count and total size: `:max-files`, `:max-bytes`); `{}`
   without a workspace. Past a bound the rest is left out, or, with
   `:strict? true`, the read throws (what must be read whole, a bundle)."
  ([room dir] (read-tree room dir nil))
  ([room dir {:keys [strict?] :as opts}]
   (if-let [fs (room-fs room)]
     (let [max-files (or (:max-files opts) max-files)
           max-bytes (or (:max-bytes opts) max-bytes)
           total (atom 0)
           over! (fn [acc what]
                   (if strict?
                     (throw (ex-info (str dir " holds more than " what " (read whole, or not at all)")
                                     {:type ::too-large :dir dir :max-files max-files :max-bytes max-bytes}))
                     (reduced acc)))]
       (letfn [(walk [path acc]
                 (reduce (fn [acc {:keys [type] entry-path :path}]
                           (cond
                             (and (<= max-files (count acc)) (#{:dir :file} type))
                             (if (= :file type) (over! acc (str max-files " files")) (walk entry-path acc))
                             (= :dir type) (walk entry-path acc)
                             (= :file type)
                             (let [text (str (mfs/read-file fs entry-path))]
                               (if (< max-bytes (swap! total + (count text)))
                                 (over! acc (str max-bytes " characters"))
                                 (assoc acc entry-path text)))
                             :else acc))
                         acc
                         ;; a listing that fails is an empty directory, or,
                         ;; strict, the read's failure
                         (try (mfs/list-dir fs path) (catch Throwable t (when strict? (throw t)) nil))))]
         (if (mfs/exists? fs dir) (walk dir {}) {})))
     {})))

(defn- write-seed-files! [fs files]
  (doseq [[path text] (sort files)]
    (let [dirs (->> (str/split path #"/") (remove str/blank?) butlast
                    (reductions #(str %1 "/" %2) "") rest)]
      (doseq [dir dirs]
        (when-not (mfs/exists? fs dir) (mfs/mkdir fs dir))))
    (when-not (mfs/write-string! fs path text false)
      (throw (ex-info (str "Could not write " path) {:type ::seed-failed :path path}))))
  nil)

(defn seed!
  "Write `files` (`{path text}`, absolute workspace paths) into `room`'s
   workspace and commit them: a merge into a dirty workspace is refused."
  [room files]
  (let [fs (or (room-fs room)
               (throw (ex-info "Room has no workspace" {:type ::no-workspace :room (:id room)})))]
    (write-seed-files! fs files)
    (forks/commit-workspace! room (str "Seed " (count files) " files"))
    (count files)))

(def fixture-seed-limits
  {:max-files 200 :max-file-bytes 8388608 :max-bytes 16777216})

(defn- refuse-seed! [reason]
  (throw (ex-info "Checkpoint fixture seeding refused" {:type ::invalid-fixture-seed :reason reason})))

(defn- fixture-utf8! [text maximum]
  ;; A character cannot need fewer UTF-8 bytes than its UTF-16 length. Refuse
  ;; huge strings before allocating the encoder's buffer, then measure bytes.
  (when (> (count text) maximum) (refuse-seed! :file-too-large))
  (try
    (let [encoder (doto (.newEncoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
          buffer (.encode encoder (CharBuffer/wrap ^String text))
          size (.remaining buffer)]
      (when (> size maximum) (refuse-seed! :file-too-large))
      (let [bytes (byte-array size)] (.get buffer bytes) bytes))
    (catch CharacterCodingException _ (refuse-seed! :invalid-utf8))))

(defn prepare-fixture-files
  "HOST preflight without selecting or writing a workspace. Return a sorted
   map of canonical paths to {:text :content :size}, using strict UTF-8 and
   finite limits. Loads the fixed canonical path validator when needed.
   These data convey neither workspace authority nor publication proof."
  [files limits]
  (when-not (and (map? limits) (= (set (keys fixture-seed-limits)) (set (keys limits)))
                 (every? (fn [[k v]] (and (integer? v) (<= 0 v (get fixture-seed-limits k)))) limits)
                 (map? files) (<= (count files) (:max-files limits)))
    (refuse-seed! :invalid-limits-or-files))
  ;; Share the existing canonical path gate without introducing a namespace
  ;; load cycle through the Room's operation surface. No alternative resolver.
  (let [path! (requiring-resolve 'dvergr.rooms.files/workspace-path)
        entries (into (sorted-map)
                      (for [[path text] files]
                        (do
                          (when-not (and (string? path) (<= 1 (count path) 512) (string? text)
                                         (= path (path! path)))
                            (refuse-seed! :noncanonical-file))
                          (fixture-utf8! path (* 3 512))
                          (let [bytes (fixture-utf8! text (:max-file-bytes limits))]
                            [path {:text text :content (blob/blob-id bytes) :size (alength bytes)}]))))]
    (when (> (reduce + 0 (map :size (vals entries))) (:max-bytes limits)) (refuse-seed! :files-too-large))
    (when (some true? (for [a (keys entries) b (keys entries) :when (not= a b)]
                        (str/starts-with? b (str a "/"))))
      (refuse-seed! :file-parent-collision))
    entries))

(defn seed-in-context!
  "HOST fixture seeding in one explicitly selected, caller-owned context.
   Preflight canonical text files and finite limits before mutation. Writes use
   only the primary Geschichte adapter, never a Room rebind or nested mount.
   Starting index/worktree must be clean. Limits may only lower hard caps.
   Return a portable written manifest and whether the logical tree changed.
   Caller owns authorization/lifetime and discards the temporary world on error;
   this is fixture initialization, not Simmis document projection or a grant."
  [files limits]
  (when (simple/on-drain-thread?) (refuse-seed! :engine-drain))
  (when-not (gs/workspace-selected?) (refuse-seed! :workspace-not-selected))
  (let [entries (prepare-fixture-files files limits)
        selected (gs/selected-system-id)
        workspace (or (gs/current-workspace) (refuse-seed! :workspace-unavailable))
        conn (:conn workspace)
        current! #(do (gs/assert-workspace-current! workspace)
                      (when-not (= selected (gs/selected-system-id)) (refuse-seed! :selection-changed)))
        fs (primary-fs/make conn)
        rel #(subs % 1)
        parents (fn [path]
                  (->> (str/split path #"/") (remove str/blank?) butlast
                       (reductions #(str %1 "/" %2) "") rest))]
    (locking (gs/workspace-lock conn)
      (current!)
      (when-not (:clean? (repo/status conn)) (refuse-seed! :dirty-workspace))
      (let [before (query/worktree @conn)
            missing-dirs (set (for [path (keys entries) parent (parents path)
                                    :when (not (mfs/exists? fs parent))] (rel parent)))
            expected (into (sorted-map)
                           (for [[path entry] entries]
                             (let [stat (mfs/stat fs path) old (get before (rel path))]
                               (when (and stat (not= :file (:type stat))) (refuse-seed! :non-file-destination))
                               (doseq [parent (parents path)]
                                 (when-let [st (mfs/stat fs parent)]
                                   (when-not (= :dir (:type st)) (refuse-seed! :non-directory-parent))))
                               [path (assoc (select-keys entry [:content :size]) :mode (or (:mode old) 33188))])))
            different (into (sorted-map)
                            (for [[path entry] entries
                                  :when (not= (get expected path) (select-keys (get before (rel path)) [:content :size :mode]))]
                              [path (:text entry)]))
            allowed (into missing-dirs (map rel (keys entries)))]
        (current!)
        (write-seed-files! fs different)
        (let [after (query/worktree @conn)
              status (repo/status conn)
              changes (vec (sort (distinct (concat (:staged status) (:unstaged status) (:untracked status)))))]
          (doseq [[path value] expected]
            (when-not (= value (select-keys (get after (rel path)) [:content :size :mode]))
              (refuse-seed! :written-file-mismatch)))
          (when (some #(not (contains? allowed %)) changes) (refuse-seed! :unexpected-changes))
          (when (seq changes)
            (repo/stage! conn changes)
            (let [index (repo/index-tree conn)]
              (doseq [[path value] expected]
                (when-not (= value (select-keys (get index (rel path)) [:content :size :mode]))
                  (refuse-seed! :staged-file-mismatch))))
            (current!)
            (let [{:keys [exit]} (gs/execute-git workspace ["commit" "-m" "Build retained benchmark fixture"])]
              (when-not (zero? exit) (refuse-seed! :commit-failed))))
          (when-not (:clean? (repo/status conn)) (refuse-seed! :publication-not-clean))
          {:files (count entries) :bytes (reduce + 0 (map :size (vals entries)))
           :changed? (boolean (seq changes)) :manifest expected})))))

(declare open-memory-workspace!)

(defn ensure-workspace!
  "`room`'s workspace: the one it has (a daemon room's forked repository), or
   a fresh in-memory one registered in its context when its store has none
   (an experiment store). The in-memory repository is not deleted afterwards:
   the evaluator's capture reads it after the Run's cleanups, so a cleanup
   cannot release it; it is the size of the fixtures."
  [room]
  (or (binding [ec/*execution-context* (:ctx room)] (gs/current-workspace))
      (do (open-memory-workspace! room)
          (binding [ec/*execution-context* (:ctx room)] (gs/current-workspace)))))

(defn open-memory-workspace!
  "Register a fresh in-memory workspace in `room`'s context (see
   `ensure-workspace!`)."
  [room]
  (let [cfg (assoc gs/repository-options :store {:backend :memory :id (random-uuid)})]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (repo/init! conn {:name "attempt workspace"})
      (binding [ec/*execution-context* (:ctx room)]
        (ygg/register! (gy/create conn {:system-name (str "room-repo-attempt-" (random-uuid))})))
      conn)))
