(ns dvergr.rooms.files
  "A room's workspace files for clients outside the room (the `room_files`,
   `room_read` and `room_write` ops: MCP, the JSON API): list a directory, read
   a file (text, or base64 for binary), and write a batch of files as one
   commit of the room's repository.

   The workspace is the room's Geschichte repository served as a Muschel
   filesystem (`dvergr.substrate.geschichte`), the one agents' file tools and
   `dvergr.catalog.workspace` use. A batch is a `:git/commit` through the
   effect boundary, decided before anything is written, and its writes and
   removals pass it again at the tools' gates (`dvergr.tools/workspace-write!`,
   `workspace-delete!`), so a world's read-only mode, quota and receipts
   decide them. Paths are workspace paths: no `..` segment, nothing under
   `.git`, no sensitive path (`dvergr.sandbox.ns.io/sensitive-path-policy`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.blob :as blob]
            [dvergr.effects :as effects]
            [dvergr.rooms.forks :as forks]
            [dvergr.sandbox.ns.io :as sio]
            [dvergr.substrate.geschichte :as gs]
            [dvergr.tools :as tools]
            [geschichte.repo :as repo]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]
           [java.util Base64]))

(def max-read-bytes
  "The most bytes one `room_read` returns (its `length` cap). The result stays
   under the MCP result limit (`dvergr.mcp.surface/max-text-chars`): as base64
   it is 4/3 of this, and as JSON text at most twice it (text holds no control
   characters but tab, newline and carriage return; others read as binary)."
  24576)

(def max-resource-bytes
  "The largest file a `room://{room}/files/{path}` resource returns whole."
  (* 4 1024 1024))

(def max-list-entries "The most entries one `room_files` returns (`offset` pages)." 200)
(def max-file-bytes "The largest file one `room_write` writes." (* 8 1024 1024))
(def max-batch-bytes "What one `room_write` writes in all." (* 16 1024 1024))
(def max-batch-files "How many paths one `room_write` names." 200)

;; ---------------------------------------------------------------------------
;; Paths and the workspace
;; ---------------------------------------------------------------------------

(defn- refuse [msg data]
  (throw (ex-info msg (assoc data :type ::invalid))))

(defn workspace-path
  "`path` (relative, or absolute from the workspace root) as an absolute
   workspace path. Refused: a `..` segment, a NUL or backslash, and what the
   sandbox's sensitive-path policy refuses (anything under `.git`). With
   `root?`, the root itself (\"\", \"/\") is allowed."
  ([path] (workspace-path path false))
  ([path root?]
   (let [p (str path)
         segs (remove #(contains? #{"" "."} %) (str/split p #"/"))]
     (when (or (str/includes? p "\u0000") (str/includes? p "\\"))
       (refuse (str "Invalid path: " (pr-str p)) {:path p}))
     (when (some #{".."} segs)
       (refuse (str "A path may not contain '..': " p) {:path p}))
     (when (and (empty? segs) (not root?))
       (refuse (str "Not a file path: " (pr-str p)) {:path p}))
     (let [abs (str "/" (str/join "/" segs))]
       (try (sio/sensitive-path-policy abs)
            (catch clojure.lang.ExceptionInfo _
              (refuse (str "Access denied: " abs " is not workspace content") {:path p})))
       abs))))

(defn- rel [abs] (subs abs 1))

(defn- workspace!
  "`room`'s workspace descriptor (`gs/current-workspace`), or throws."
  [room]
  (or (binding [ec/*execution-context* (:ctx room)] (gs/current-workspace))
      (throw (ex-info (str "Room " (some-> (:id room) name) " has no workspace")
                      {:type ::no-workspace :room (:id room)}))))

(defn- content-ids
  "`{absolute-path content-id}` of the workspace's files."
  [conn]
  (into {} (map (fn [[p {:keys [content]}]] [(str "/" p) (some-> content str)]))
        (repo/worktree conn)))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn list-dir
  "The entries of directory `path` (default the root) in `room`'s workspace:
   `{:path :total :entries [{:name :path :kind \"file\"|\"dir\" :size
   :content-id}]}`, directories first, at most `max-list-entries` from
   `offset` (`:next-offset` when more follow). A file's `content-id` is the
   hash of its bytes."
  ([room path] (list-dir room path {}))
  ([room path {:keys [offset]}]
   (let [{:keys [conn] :as ws} (workspace! room)
         fs (gs/filesystem ws)
         dir (workspace-path (or path "/") true)
         st (mfs/stat fs dir)
         offset (long (or offset 0))]
     (when-not (= :dir (:type st))
       (throw (ex-info (str (if st "Not a directory: " "No such directory: ") dir)
                       {:type (if st ::not-a-directory ::not-found) :path dir})))
     (when (neg? offset) (refuse "offset must not be negative" {:offset offset}))
     (let [ids (content-ids conn)
           all (vec (remove #(= ".git" (:name %)) (mfs/list-dir fs dir)))
           page (take max-list-entries (drop offset all))
           end (+ offset (count page))]
       (cond-> {:path dir
                :total (count all)
                :entries (vec (for [{entry-name :name entry-type :type size :size} page
                                    :let [p (str (when-not (= "/" dir) dir) "/" entry-name)
                                          file? (= :file entry-type)]]
                                (cond-> {:name entry-name :path p :kind (name (or entry-type :file))}
                                  file? (assoc :size size :content-id (get ids p)))))}
         (< end (count all)) (assoc :next-offset end))))))

(def ^:private media-types
  {"csv" "text/csv" "tsv" "text/tab-separated-values" "txt" "text/plain" "md" "text/markdown"
   "edn" "application/edn" "clj" "text/x-clojure" "cljc" "text/x-clojure" "json" "application/json"
   "jsonl" "application/jsonl" "html" "text/html" "xml" "application/xml" "yaml" "application/yaml"
   "yml" "application/yaml" "pdf" "application/pdf" "png" "image/png" "jpg" "image/jpeg"
   "jpeg" "image/jpeg" "gif" "image/gif" "svg" "image/svg+xml" "zip" "application/zip"
   "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
   "xls" "application/vnd.ms-excel"
   "docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   "pptx" "application/vnd.openxmlformats-officedocument.presentationml.presentation"
   "ods" "application/vnd.oasis.opendocument.spreadsheet"})

(defn media-type
  "A media type for `path` from its extension; `text?` picks the fallback."
  [path text?]
  (or (get media-types (some-> (re-find #"\.([^./]+)$" (str path)) second str/lower-case))
      (if text? "text/plain" "application/octet-stream")))

(defn- text-bytes?
  "Whether `bytes` are text: they decode strictly as UTF-8, and hold no control
   character but tab, newline and carriage return (which JSON would escape to
   six characters each)."
  [^bytes bytes]
  (try
    (let [text (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                               (.onMalformedInput CodingErrorAction/REPORT)
                               (.onUnmappableCharacter CodingErrorAction/REPORT))
                             (ByteBuffer/wrap bytes)))]
      (not (re-find #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" text)))
    (catch CharacterCodingException _ false)))

(defn- continuation? [^bytes bytes i]
  (and (< i (alength bytes)) (= 0x80 (bit-and (aget bytes i) 0xC0))))

(defn read-file
  "Read file `path` of `room`'s workspace: `{:path :size :content-id
   :media-type :offset :length}` with `:text` (UTF-8 text) or `:base64`
   (anything else), from byte `offset` (default 0) for at most `length` bytes
   (default and cap `max`, by default `max-read-bytes`). Text is read whole
   characters at a time: an offset inside a character, or a length too short
   for the character at `offset`, is refused. When the file goes on,
   `:next-offset` says where."
  [room path {:keys [offset length max] :or {max max-read-bytes}}]
  (let [{:keys [conn] :as ws} (workspace! room)
        fs (gs/filesystem ws)
        p (workspace-path path)
        st (mfs/stat fs p)]
    (case (:type st)
      :dir (throw (ex-info (str "A directory: " p " (room_files lists it)") {:type ::directory :path p}))
      nil (throw (ex-info (str "No such file: " p) {:type ::not-found :path p}))
      nil)
    (let [^bytes bytes (or (mfs/read-bytes fs p) (byte-array 0))
          size (alength bytes)
          offset (long (or offset 0))
          length (long (min max (or length max)))
          _ (when (or (neg? offset) (and (pos? offset) (>= offset size)))
              (refuse (str "offset " offset " is outside " p " (" size " bytes)") {:path p :offset offset}))
          _ (when-not (pos? length) (refuse "length must be positive" {:length length}))
          text? (text-bytes? bytes)
          _ (when (and text? (continuation? bytes offset))
              (refuse (str "offset " offset " is inside a character of " p) {:path p :offset offset}))
          end (min size (+ offset length))
          ;; text ends before a character that does not fit whole
          end (if text? (loop [e end] (if (continuation? bytes e) (recur (dec e)) e)) end)
          _ (when (and (= end offset) (< offset size))
              (refuse (str "length " length " is shorter than the character at offset " offset)
                      {:path p :offset offset :length length}))
          n (- end offset)]
      (cond-> {:path p :size size :content-id (get (content-ids conn) p)
               :media-type (media-type p text?) :offset offset :length n}
        text? (assoc :text (String. bytes (int offset) (int n) StandardCharsets/UTF_8))
        (not text?) (assoc :base64 (.encodeToString (Base64/getEncoder)
                                                    (java.util.Arrays/copyOfRange bytes (int offset) (int end))))
        (< end size) (assoc :next-offset end)))))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(defn- field [m k] (or (get m k) (get m (name k))))

(defn- key->path
  "A `files` key as given: a string, or a keyword as a JSON body decodes it
   (`:data/cases.csv` keeps its namespace)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- content
  "A `files` value as the bytes to write, or `::remove` (nil)."
  [path v]
  (cond
    (nil? v) ::remove
    (map? v)
    (let [text (field v :text) b64 (field v :base64)]
      (cond
        (and (some? text) (some? b64)) (refuse (str path ": give text or base64, not both") {:path path})
        (string? text) (.getBytes ^String text StandardCharsets/UTF_8)
        (string? b64) (try (.decode (Base64/getDecoder) (str/replace ^String b64 #"\s" ""))
                           (catch IllegalArgumentException _
                             (refuse (str path ": base64 does not decode") {:path path})))
        :else (refuse (str path ": content is {text} or {base64}, or null to remove it") {:path path})))
    :else (refuse (str path ": content is {text} or {base64}, or null to remove it") {:path path})))

(defn- size-of [c] (if (bytes? c) (alength ^bytes c) 0))

(defn- plan
  "The validated batch: `[[absolute-path bytes-or-::remove] …]`, sorted."
  [files]
  (when-not (and (map? files) (seq files))
    (refuse "files is a map of path to content, with at least one path" {}))
  (when (< max-batch-files (count files))
    (refuse (str "At most " max-batch-files " paths per room_write") {:count (count files)}))
  (let [entries (for [[k v] files :let [p (workspace-path (key->path k))]] [p (content p v)])
        dupes (keep (fn [[p n]] (when (< 1 n) p)) (frequencies (map first entries)))]
    (when (seq dupes)
      (refuse (str "Paths name the same file: " (str/join ", " dupes)) {:paths (vec dupes)}))
    (doseq [[p c] entries :when (< max-file-bytes (size-of c))]
      (refuse (str p " is larger than " max-file-bytes " bytes") {:path p}))
    (when (< max-batch-bytes (reduce + (map (comp size-of second) entries)))
      (refuse (str "A room_write writes at most " max-batch-bytes " bytes") {}))
    (vec (sort-by first entries))))

(defn- ancestors-of [abs]
  (->> (str/split abs #"/") (remove str/blank?) butlast (reductions #(str %1 "/" %2) "") rest))

(defn- check-shape!
  "Refuse a batch that would overwrite or remove a directory, or put a file
   beneath a file."
  [fs entries]
  (doseq [[p c] entries
          :let [t (:type (mfs/stat fs p))]]
    (when (and t (not= :file t))
      (refuse (str p " is a directory; name its files") {:path p}))
    (when (not= ::remove c)
      (when-let [blocked (some #(when (= :file (:type (mfs/stat fs %))) %) (ancestors-of p))]
        (refuse (str p ": " blocked " is a file") {:path p})))))

(defn- restore!
  "Undo this batch's changes: the index of its paths back to HEAD (it held
   nothing else staged), the files it wrote or removed back to `before`
   unless someone else changed them since (their content is no longer what
   this batch left), and the directories it created, when empty."
  [conn fs before expected touched created]
  (try (repo/restore-paths! conn (mapv rel (keys expected)) {:source :head :staged? true :worktree? false})
       (catch Throwable _ nil))
  (let [now (content-ids conn)
        ours (filter #(= (get expected %) (get now %)) touched)
        tx (vec (keep (fn [p]
                        (if-let [{:keys [content size mode]} (get before p)]
                          {:geschichte.work/path (rel p) :geschichte.work/content [:geschichte.content/id content]
                           :geschichte.work/size size :geschichte.work/mode mode}
                          (when (contains? now p) [:db/retractEntity [:geschichte.work/path (rel p)]])))
                      ours))]
    (try (when (seq tx) (d/transact conn tx)) (catch Throwable _ nil))
    (doseq [dir (sort-by count > created)]
      (try (when (empty? (mfs/list-dir fs dir)) (mfs/delete fs dir)) (catch Throwable _ nil)))))

(defn host-boundary
  "The effect boundary for a write made on `room` by a client outside it: the
   handlers of the room's world (read-only, quota, …) with receipts into
   `sink` and the world's sink."
  [room sink]
  (effects/boundary-resolver nil sink {:world #(effects/world-handlers (:ctx room))
                                       :world-sink #(effects/world-sink (:ctx room))}))

(defn write!
  "Write `files` (`{path content}`; content `{:text …}`, `{:base64 …}`, or nil
   to remove the file) into `room`'s workspace and commit exactly those paths
   as one commit with `message` (`forks/commit-paths!`). The batch is a
   `:git/commit` effect, decided before anything is written; each write and
   removal is an effect too. Every path is checked first; the index may hold
   nothing staged beyond the batch. When any step fails, the batch's changes
   are undone (`restore!`) and nothing is committed. Returns `{:commit
   :message :written [{:path :size :content-id}] :removed [path]}`;
   `:commit` is nil when the files already were as given."
  [room files {:keys [message boundary]}]
  (let [entries (plan files)
        {:keys [conn] :as ws} (workspace! room)
        fs (gs/filesystem ws)
        boundary (or boundary (host-boundary room (effects/make-sink)))
        ctx {:filesystem fs :cwd "/" :effect-boundary boundary}
        paths (mapv first entries)
        message (or (not-empty (some-> message str str/trim))
                    (str "Write " (if (= 1 (count paths)) (rel (first paths)) (str (count paths) " files"))))
        expected (into {} (for [[p c] entries] [p (when (bytes? c) (str (blob/blob-id c)))]))]
    ;; one batch at a time per repository, and no git operation of dvergr's
    ;; in between (`gs/workspace-lock`)
    (locking (gs/workspace-lock conn)
      (check-shape! fs entries)
      (let [staged (remove (set (map rel paths)) (:staged (repo/status conn)))]
        (when (seq staged)
          (throw (ex-info (str "The workspace has staged changes (" (str/join ", " (take 5 staged))
                               "); commit them before a room_write")
                          {:type ::staged-changes :staged (vec staged)}))))
      (let [worktree (repo/worktree conn)
            before (into {} (keep (fn [p] (when-let [e (get worktree (rel p))] [p e]))) paths)
            created (into #{} (comp (mapcat (fn [[p c]] (when (not= ::remove c) (ancestors-of p))))
                                    (remove #(mfs/exists? fs %)))
                          entries)
            touched (atom [])
            removed (atom [])
            commit (effects/perform!
                    boundary {:effect :git/commit :resource {:message message}}
                    (fn []
                      (try
                        (doseq [[p c] entries]
                          (if (= ::remove c)
                            (when (tools/workspace-delete! ctx p)
                              (swap! touched conj p)
                              (swap! removed conj p))
                            (do (tools/workspace-write! ctx p c)
                                (swap! touched conj p))))
                        (forks/commit-paths! room expected message)
                        (catch Throwable e
                          (restore! conn fs before expected @touched created)
                          (throw e)))))
            ids (content-ids conn)]
        {:commit commit
         :message message
         :written (vec (for [[p c] entries :when (not= ::remove c)]
                         {:path p :size (size-of c) :content-id (get ids p)}))
         :removed @removed}))))
