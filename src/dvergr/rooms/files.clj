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
            [datahike.db.interface :as dbi]
            [dvergr.effects :as effects]
            [dvergr.system.rooms :as srooms]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.publication :as publication]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.rooms.subtree :as subtree]
            [dvergr.sandbox.ns.io :as sio]
            [dvergr.substrate.geschichte :as gs]
            [dvergr.tools :as tools]
            [geschichte.repo :as repo]
            [geschichte.content :as content]
            [geschichte.fs :as gfs]
            [geschichte.query :as query]
            [jsonista.core :as json]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple])
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

(def ^:private max-list-chars
  "What one `room_files` page's entries may take as JSON: a page ends before
   this, so the result fits the MCP result limit whatever the names' length."
  40000)
(def max-file-bytes "The largest file one `room_write` writes." (* 8 1024 1024))
(def max-batch-bytes "What one `room_write` writes in all." (* 16 1024 1024))
(def max-batch-files "How many paths one `room_write` names." 200)
(def write-capabilities
  "Host embedding contracts supported by the canonical batch writer."
  #{:expected-worktree :applied-receipt :file-modes :durable-publication :explicit-workspace :expected-subtree :validated-basis})

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
     ;; Geschichte drops blank segments: `.env/ ` would name `.env`
     (when (some str/blank? segs)
       (refuse (str "A path segment may not be blank: " (pr-str p)) {:path p}))
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
           entry (fn [{entry-name :name entry-type :type size :size}]
                   (let [p (str (when-not (= "/" dir) dir) "/" entry-name)]
                     (cond-> {:name entry-name :path p :kind (name (or entry-type :file))}
                       (= :file entry-type) (assoc :size size :content-id (get ids p)))))
           ;; whole entries, at least one, until the count or the size runs out
           page (loop [es (drop offset all) acc [] chars 0]
                  (if-let [e (some-> (first es) entry)]
                    (let [n (+ chars 1 (count (json/write-value-as-string e)))]
                      (if (and (seq acc) (or (<= max-list-entries (count acc)) (< max-list-chars n)))
                        acc
                        (recur (rest es) (conj acc e) n)))
                    acc))
           end (+ offset (count page))]
       (cond-> {:path dir :total (count all) :entries page}
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

(def ^:private symlink-mode "Git's mode of a symbolic link (0120000)." 0120000)

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
        p (workspace-path path)
        ;; one worktree entry, read by its content id: what is checked is
        ;; what is read, whatever replaces the path meanwhile
        {:keys [content mode] :as entry} (get (repo/worktree conn) (rel p))]
    (cond
      (nil? entry)
      (if (= :dir (:type (mfs/stat (gs/filesystem ws) p)))
        (throw (ex-info (str "A directory: " p " (room_files lists it)") {:type ::directory :path p}))
        (throw (ex-info (str "No such file: " p) {:type ::not-found :path p})))
      ;; a link's target is not checked against the path rules: not followed
      (= symlink-mode (bit-and (long (or mode 0)) 0170000))
      (refuse (str p " is a symbolic link, which room_read does not follow") {:path p}))
    (let [^bytes bytes (or (repo/read-entry conn entry) (byte-array 0))
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
      (cond-> {:path p :size size :content-id (str content)
               :media-type (media-type p text?) :offset offset :length n}
        text? (assoc :text (String. bytes (int offset) (int n) StandardCharsets/UTF_8))
        (not text?) (assoc :base64 (.encodeToString (Base64/getEncoder)
                                                    (java.util.Arrays/copyOfRange bytes (int offset) (int end))))
        (< end size) (assoc :next-offset end)))))

;; ---------------------------------------------------------------------------
(def max-read-index-entries
  "Metadata bound of explicit flat-repository read projections. Legacy selected
   MountFS reads retain their existing behavior, including nested mounts."
  20000)

(defn immutable-workspace-db?
  "Whether a value supports immutable Datahike projections. This is a storage
   shape check, NOT authorization or a proof of repository ownership."
  [db]
  (and (satisfies? dbi/IDB db) (satisfies? dbi/ISearch db)))

(defn- frozen-workspace! [db]
  (when-not (immutable-workspace-db? db)
    (refuse "An immutable workspace database is required" {}))
  (doseq [attribute [:geschichte.work/path :geschichte.work-dir/path]]
    (when (> (count (take (inc max-read-index-entries) (d/datoms db :avet attribute)))
             max-read-index-entries)
      (refuse "Workspace metadata exceeds the explicit read limit" {})))
  ;; Geschichte's read APIs take an IDeref. This local frozen value is never
  ;; exposed, mutated, registered as a system, or used as a Room facade.
  (atom db))

(defn list-dir-in
  "HOST STORAGE primitive: bounded listing of ONE immutable native repository,
   including its explicit directories. No selected workspace or nested MountFS
   lookup. Caller must establish current authority and canonical ownership.
   The snapshot is not a GC pin; it is never a retained authorization lease."
  ([db path] (list-dir-in db path {}))
  ([db path {:keys [offset]}]
   (let [frozen (frozen-workspace! db)
         dir (workspace-path (or path "/") true)
         st (gfs/stat frozen dir)
         offset (or offset 0)]
     (when-not (and (integer? offset) (<= 0 offset max-read-index-entries))
       (refuse "Invalid directory offset" {}))
     (when-not (= :dir (:type st))
       (throw (ex-info "Workspace directory not found"
                       {:type (if st ::not-a-directory ::not-found)})))
     (let [tree (query/worktree db)
           all (vec (remove #(= ".git" (:name %)) (gfs/list-dir frozen dir)))
           entry (fn [{entry-name :name entry-type :type size :size}]
                   (let [p (str (when-not (= "/" dir) dir) "/" entry-name)]
                     (cond-> {:name entry-name :path p :kind (name (or entry-type :file))}
                       (= :file entry-type)
                       (assoc :size size :content-id (some-> (get tree (rel p)) :content str)))))
           page (loop [es (drop offset all) acc [] chars 0]
                  (if-let [e (some-> (first es) entry)]
                    (let [entry-chars (inc (count (json/write-value-as-string e)))
                          _ (when (> entry-chars max-list-chars)
                              (refuse "Workspace listing entry exceeds the explicit read limit" {}))
                          n (+ chars entry-chars)]
                      (if (and (seq acc) (or (<= max-list-entries (count acc)) (< max-list-chars n)))
                        acc (recur (rest es) (conj acc e) n))) acc))
           end (+ offset (count page))]
       (cond-> {:path dir :total (count all) :entries page}
         (< end (count all)) (assoc :next-offset end))))))

(defn read-file-in
  "HOST STORAGE primitive: raw bytes of one file in a frozen native repository.
   Same bounded text/binary chunk format as read-file; never consults selection,
   live filesystem mounts or a newer content entity. Validate metadata BEFORE
   decoding a body. :whole? is trusted resource policy, not a client option.
   Caller supplies current authorization/ownership; no interpreter or write."
  [db path {:keys [offset length max whole?] :or {max max-read-bytes}}]
  (let [frozen (frozen-workspace! db)
        p (workspace-path path)
        {:keys [content mode size] :as entry} (get (query/worktree db) (rel p))
        offset (or offset 0)
        length (or length max)]
    (when-not (and (integer? max) (<= 1 max max-resource-bytes)
                   (integer? offset) (<= 0 offset max-file-bytes)
                   (integer? length) (pos? length))
      (refuse "Invalid file read bounds" {}))
    (when-not entry
      (throw (ex-info "Workspace file not found"
                      {:type (if (= :dir (:type (gfs/stat frozen p))) ::directory ::not-found)})))
    (when-not (and (uuid? content) (integer? size) (<= 0 size max-file-bytes)
                   (integer? mode) (= 0100000 (bit-and mode 0170000)))
      (refuse "Only bounded regular workspace files can be read" {}))
    (when (and whole? (> size max))
      (refuse "File exceeds the whole-resource limit; use room_read chunks" {}))
    (when-not (= size (:geschichte.content/size (d/entity db [:geschichte.content/id content])))
      (refuse "Workspace content metadata is inconsistent" {}))
    (let [^bytes bytes (content/read-by-id frozen content)
          _ (when-not (and bytes (= size (alength bytes)))
              (refuse "Workspace content is unavailable or inconsistent" {}))
          length (long (min max length))
          _ (when (and (pos? offset) (>= offset size)) (refuse "File offset is outside the file" {}))
          text? (text-bytes? bytes)
          _ (when (and text? (continuation? bytes offset))
              (refuse "File offset is inside a character" {}))
          end (min size (+ offset length))
          end (if text? (loop [e end] (if (continuation? bytes e) (recur (dec e)) e)) end)
          _ (when (and (= end offset) (< offset size)) (refuse "Read length splits a character" {}))
          n (- end offset)]
      (cond-> {:path p :size size :content-id (str content)
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
    (let [written (set (keep (fn [[p c]] (when (not= ::remove c) p)) entries))]
      (doseq [[p c] entries
              :when (not= ::remove c)
              :let [parent (some written (file-tx/ancestors-of p))]
              :when parent]
        (refuse (str p ": the batch also writes " parent " as a file") {:path p})))
    (doseq [[p c] entries :when (< max-file-bytes (size-of c))]
      (refuse (str p " is larger than " max-file-bytes " bytes") {:path p}))
    (when (< max-batch-bytes (reduce + (map (comp size-of second) entries)))
      (refuse (str "A room_write writes at most " max-batch-bytes " bytes") {}))
    (vec (sort-by first entries))))

(defn- check-shape!
  "Refuse a batch that would overwrite or remove a directory or a symbolic
   link, or reach a path beneath a file or a link."
  [fs entries]
  (doseq [[p c] entries
          :let [t (:type (mfs/stat fs p))]]
    (case t
      (nil :file) nil
      :dir (refuse (str p " is a directory; name its files") {:path p})
      (refuse (str p " is a symbolic link; room_write does not write through or remove links") {:path p}))
    (when-let [[blocked bt] (some #(let [t (:type (mfs/stat fs %))] (when (and t (not= :dir t)) [% t]))
                                  (file-tx/ancestors-of p))]
      (refuse (str p ": " blocked " is " (if (= :file bt) "a file" "a symbolic link")) {:path p}))))

(defn- restore-tx
  "Transaction function: put the worktree entries of `paths` back as they
   were `before`, each only while it still holds what the batch left there
   (`expected`). Run in the transactor, so a writer that changed a path since
   keeps its change."
  [db before expected paths]
  (vec (mapcat (fn [p]
                 (let [[eid cid mode] (first (d/q '[:find ?e ?cid ?m :in $ ?p
                                                    :where [?e :geschichte.work/path ?p]
                                                    [?e :geschichte.work/content ?c]
                                                    [?c :geschichte.content/id ?cid]
                                                    [?e :geschichte.work/mode ?m]]
                                                  db (rel p)))]
                   ;; the batch's own: its content, as a regular file (never a link)
                   (when (and (= (some-> cid str) (get expected p))
                              (not= symlink-mode (bit-and (long (or mode 0)) 0170000)))
                     (if-let [{:keys [content size mode]} (get before p)]
                       ;; a removed file comes back only where its path can take a file
                       ;; again: no directory there, and only directories above it
                       (when (or eid (nil? (tools/shape-conflict db (rel p))))
                         [{:geschichte.work/path (rel p) :geschichte.work/content [:geschichte.content/id content]
                           :geschichte.work/size size :geschichte.work/mode mode}])
                       (when eid [[:db/retractEntity eid]])))))
               paths)))

(defn- restore!
  "Undo this batch's changes: the index of its paths back to HEAD (where it
   was: nothing was staged), the files it wrote or removed back to `before`
   unless someone else changed them since (`restore-tx`), and the
   directories it created, when empty. Returns the paths it could not put
   back (changed meanwhile, or a directory now in a removed file's place)."
  [conn before expected touched created]
  (try (repo/restore-paths! conn (mapv rel (keys expected)) {:source :head :staged? true :worktree? false})
       (catch Throwable _ nil))
  (try (when (seq touched) (d/transact conn [[:db.fn/call restore-tx before expected (vec touched)]]))
       (catch Throwable _ nil))
  (try (when (seq created) (d/transact conn [[:db.fn/call file-tx/remove-empty-dirs-tx (vec created)]]))
       (catch Throwable _ nil))
  (let [now (content-ids conn)]
    (vec (sort (remove #(= (get now %) (some-> (get before %) :content str)) touched)))))

(defn host-boundary
  "The effect boundary for a write made on `room` by a client outside it: the
   handlers of the room's world (read-only, quota, …) with receipts into
   `sink` and the world's sink."
  [room sink]
  (effects/boundary-resolver nil sink {:world #(effects/world-handlers (:ctx room))
                                       :world-sink #(effects/world-sink (:ctx room))}))

(defn- opened-worktree!
  [paths opts]
  (when (contains? opts :expected-worktree)
    (let [expected (:expected-worktree opts)]
      (when-not (and (map? expected) (= (set paths) (set (keys expected)))
                     (every? (fn [[path entry]]
                               (and (string? path)
                                    (or (nil? entry)
                                        (and (map? entry) (= #{:content :size :mode} (set (keys entry)))
                                             (uuid? (:content entry))
                                             (integer? (:size entry)) (<= 0 (:size entry))
                                             (integer? (:mode entry)))))) expected))
        (refuse "expected-worktree must describe exactly the batch's absolute paths" {}))
      expected)))

(defn- publication-result
  [receipt message]
  {:commit (some-> (:publication/commit receipt) str)
   :applied? true :recovered? true :publication receipt :message message
   :written (mapv (fn [entry]
                    (let [content (get-in entry [:publication.entry/content :geschichte.content/id])]
                      {:path (:publication.entry/path entry)
                       :size (:publication.entry/size entry) :content-id (str content)
                       :entry {:content content :size (:publication.entry/size entry)
                               :mode (:publication.entry/mode entry)}}))
                  (filter #(= :present (:publication.entry/state %)) (:publication/entries receipt)))
   :removed (mapv :publication.entry/path
                  (filter #(= :deleted (:publication.entry/state %)) (:publication/entries receipt)))})

(defn- applied-result [entries receipt message removed]
  (cond-> {:commit (when receipt (:commit receipt))
           :applied? (boolean receipt)
           :message message
           :written (vec (for [[p c] entries :when (and receipt (not= ::remove c))
                               :let [entry (get-in receipt [:entries p])]]
                           {:path p :size (:size entry) :content-id (some-> (:content entry) str)
                            :entry entry}))
           :removed removed}
    (:publication receipt) (assoc :publication (:publication receipt))
    (:subtree receipt) (assoc :subtree (:subtree receipt))))

(defn- completion-gate []
  {:owner (Thread/currentThread)
   :state (atom {:sealed? false :active 0 :entered? false :positive nil})})

(defn- guarded-native! [gate mutation? f]
  (if-not gate
    (f)
    (let [state (:state gate)]
      (locking state
        (when-not (and (identical? (:owner gate) (Thread/currentThread))
                       (not (:sealed? @state)))
          (throw (ex-info "Native file forwarding is closed or on another thread"
                          {:type ::closed-forwarding})))
        (swap! state #(cond-> (update % :active inc) mutation? (assoc :entered? true))))
      ;; Never retain the monitor across a handler/native call: a handler can
      ;; wait for a foreign callback, which must be able to refuse immediately.
      (try (f)
           (finally (locking state (swap! state update :active dec)))))))

(defn- guarded-boundary [boundary gate mutation?]
  (if-not gate
    boundary
    (fn []
      (update (or (when boundary (boundary)) {}) :handlers
              #(conj (vec %) (fn [effect next]
                               (guarded-native! gate mutation? (fn [] (next effect)))))))))

(defn- observe-positive! [gate result]
  ;; Called only by the native receipt path, BEFORE returning through handlers.
  (when gate
    (locking (:state gate)
      (swap! (:state gate) assoc :positive result)))
  result)

(defn- seal-completion! [gate]
  (locking (:state gate)
    (swap! (:state gate) assoc :sealed? true)))

(defn- receipt-schema-present? [db]
  (some? (d/q '[:find ?e . :where [?e :db/ident :publication/id]] db)))

(declare interrupted-failure?)

(defn- write-workspace!
  "Shared native batch writer. Every physical operation retains this exact
   descriptor; neither file effects nor commit re-resolve ambient selection."
  [room workspace files {:keys [message boundary file-modes publication expected-subtree validated-basis] :as opts} completion]
  (let [entries (plan files)
        planned entries
        _ (when (contains? opts :validated-basis)
            (file-tx/validate-basis! validated-basis)
            (when-not (and (= 1 (count entries))
                           (bytes? (second (first entries)))
                           (not (contains? opts :publication))
                           (not (contains? opts :expected-subtree))
                           (let [opened (opened-worktree! [(ffirst entries)] opts)
                                 entry (get opened (ffirst entries))]
                             (and entry (#{33188 33261} (:mode entry)))))
              (refuse "validated-basis requires one existing regular-file replacement with expected-worktree, without publication/subtree" {})))
        basis-write-state (when validated-basis (volatile! :not-dispatched))
        _ (when (contains? opts :expected-subtree) (subtree/validate! expected-subtree))
        ;; This option is fixed data, never a caller predicate. Validate native
        ;; accessible paths and derive AFTER without a raced repository reread.
        _ (when expected-subtree
            (when-not (= (subs (:prefix expected-subtree) 0 (dec (count (:prefix expected-subtree))))
                         (workspace-path (:prefix expected-subtree)))
              (refuse "Invalid subtree prefix" {}))
            (doseq [[path _] (:entries expected-subtree)]
              (when-not (= (str (:prefix expected-subtree) path)
                           (workspace-path (str (:prefix expected-subtree) path)))
                (refuse "Invalid subtree entry" {})))
            (when-not (every? #(str/starts-with? (first %) (:prefix expected-subtree)) entries)
              (refuse "The write batch escapes its expected subtree" {})))
        ;; Preserve the original mode under this new contract; legacy options
        ;; and behavior remain unchanged when no subtree was supplied.
        file-modes (if expected-subtree
                     (reduce (fn [m [path value]]
                               (if (= ::remove value) m
                                   (if (contains? m path) m
                                       (assoc m path (or (get-in expected-subtree
                                                                 [:entries (subs path (count (:prefix expected-subtree))) :mode])
                                                         33188)))))
                             (or file-modes {}) entries)
                     file-modes)
        required-subtree
        (when expected-subtree
          (subtree/validate!
           (update expected-subtree :entries
                   (fn [original]
                     (reduce (fn [tree [path value]]
                               (let [relative (subs path (count (:prefix expected-subtree)))]
                                 (if (= ::remove value) (dissoc tree relative)
                                     (assoc tree relative {:content (blob/blob-id value) :size (long (alength ^bytes value))
                                                           :mode (get file-modes path)})))) original entries)))))
        subtree-basis (when expected-subtree (volatile! nil))
        stage-witness (when expected-subtree (volatile! false))
        {:keys [conn] :as ws} (gs/assert-workspace-current! workspace)
        system-id (binding [ec/*execution-context* (:ctx room)]
                    (srooms/connection-system-id conn))
        _ (binding [ec/*execution-context* (:ctx room)]
            (when-let [reason (srooms/room-effect-write-refusal
                               room {:effect :room/write :resource {:system-id system-id}})]
              (effects/deny! {:effect :room/write :resource {:system-id system-id}}
                             :trunk-write-policy reason)))
        fs (gs/filesystem ws)
        boundary (or boundary (host-boundary room (effects/make-sink)))
        outer-boundary (guarded-boundary boundary completion false)
        inner-boundary (guarded-boundary boundary completion true)
        ;; `:workspace`: the gate's compare-and-write needs the repository
        ctx (cond-> {:filesystem fs :workspace ws :system-id system-id :cwd "/" :effect-boundary inner-boundary}
              validated-basis (assoc :validated-basis validated-basis :basis-write-state basis-write-state))
        commit-publication (if (and completion (fn? (:before-dispatch! publication)))
                             (update publication :before-dispatch!
                                     (fn [hook]
                                       (fn [candidate]
                                         ;; The journal may commit applying and
                                         ;; then throw before publication starts.
                                         (guarded-native! completion true #(hook candidate)))))
                             publication)
        paths (mapv first entries)
        opened (opened-worktree! paths opts)
        _ (when (and (contains? opts :file-modes)
                     (not (and (map? file-modes)
                               (every? (fn [[p mode]] (and (some #{p} paths) (#{33188 33261} mode))) file-modes))))
            (refuse "file-modes names batch paths and regular file modes only" {}))
        message (or (not-empty (some-> message str str/trim))
                    (str "Write " (if (= 1 (count paths)) (rel (first paths)) (str (count paths) " files"))))
        expected (into {} (for [[p c] entries] [p (when (bytes? c) (str (blob/blob-id c)))]))]
    (when (and publication
               (not (and (map? publication) (map? (:identity publication))
                         (fn? (:authorize! publication)) (fn? (:before-dispatch! publication)))))
      (refuse "Durable publication requires host identity, authorization and journaling hooks" {}))
    ;; This monitor serializes participating Dvergr operations, not arbitrary
    ;; filesystem or external repository writers. Writer-side basis checks are
    ;; the atomic publication guard against those other writers.
    (locking (gs/workspace-lock conn)
      (gs/assert-workspace-current! ws)
      (when publication
        (publication/authorize! publication)
        (if (or completion expected-subtree)
          (publication/validate-identity! (:identity publication))
          (publication/install! conn)))
      (if-let [recovered (when (and publication
                                    (or (and (nil? completion) (nil? expected-subtree))
                                        (receipt-schema-present? @conn)))
                           (publication/recover @conn (:identity publication)))]
        (observe-positive! completion (publication-result recovered message))
        (do
          ;; Exact scoped dedup above precedes NEW source validation. A past
          ;; receipt is not a fresh inspection of a later changed workflow.
          (when expected-subtree
            ;; A clean HEAD/index proof and original rows from ONE immutable
            ;; snapshot, never a fresh index captured after a loose check.
            (let [initial-db @conn
                  _ (subtree/assert-before! initial-db expected-subtree)
                  initial (repo/prepare-commit (atom initial-db) {:message message})
                  index-prefix (subtree/prefix-tree! initial-db (:prefix expected-subtree) true)]
              (when-not (:noop? initial)
                (throw (ex-info "Conditional subtree requires a clean initial index" {:type ::staged-changes})))
              (vreset! subtree-basis {:before expected-subtree :plan initial
                                      :index-prefix index-prefix :stage-rows (subtree/stage-rows initial-db paths)})))
          (check-shape! fs entries)
      ;; the undo restores the index to HEAD: so it must be at HEAD
          (let [staged (when-not expected-subtree (:staged (repo/status conn)))]
            (when (seq staged)
              (throw (ex-info (str "The workspace has staged changes (" (str/join ", " (take 5 staged))
                                   "); commit them before a room_write")
                              {:type ::staged-changes :staged (vec staged)}))))
          (let [worktree (if expected-subtree (:worktree (:plan @subtree-basis)) (repo/worktree conn))
                before (into {} (keep (fn [p] (when-let [e (get worktree (rel p))] [p e]))) paths)
                _ (when (and opened
                             (not= opened (into {} (map (fn [p] [p (get before p)])) paths)))
                    (throw (ex-info "Files changed since they were opened; reload before saving"
                                    {:type ::stale-worktree :paths paths})))
                created (into #{} (comp (mapcat (fn [[p c]] (when (not= ::remove c) (file-tx/ancestors-of p))))
                                        (remove #(mfs/exists? fs %)))
                              entries)
                touched (atom [])
                removed (atom [])
                applied (volatile! nil)
                execution-error (volatile! nil)
                ;; A handler may forward more than once. This one invocation
                ;; owns exactly one physical batch, including its exception.
                execute (delay
                          ;; An outer effect handler can change registration
                          ;; before forwarding. Refuse before the first write,
                          ;; without even issuing a compensating index mutation.
                          (try (gs/assert-workspace-current! ws)
                               (when expected-subtree (subtree/assert-before! @conn expected-subtree))
                               (catch Throwable error
                                 (vreset! execution-error error)
                                 (throw error)))
                          ;; The new completion API keeps schema installation
                          ;; after pure preflight and actual outer forwarding.
                          ;; Legacy callers retain their original ordering.
                          (when (and (or completion expected-subtree) publication)
                            (try (guarded-native! completion true #(publication/install! conn))
                                 (catch Throwable error
                                   (vreset! execution-error error)
                                   (throw error))))
                          (try
                            (doseq [[p c] entries
                                ;; compare-and-write: what the snapshot saw, or fail
                                    :let [opts (cond-> {:expect (some-> (get before p) :content str)}
                                                 (contains? file-modes p) (assoc :mode (get file-modes p)))]]
                              (if (= ::remove c)
                                (when (tools/workspace-delete! ctx p opts)
                                  (swap! touched conj p)
                                  (swap! removed conj p))
                                (do (tools/workspace-write! ctx p c opts)
                                    (when (and basis-write-state (not= :written @basis-write-state))
                                      (throw (ex-info "Validated file write was not forwarded"
                                                      {:type ::not-applied})))
                                    (swap! touched conj p))))
                            (let [{:keys [commit tree publication]}
                                  (guarded-native! completion true
                                                   #(forks/commit-paths-in-workspace!
                                                     room ws expected message
                                                     (cond-> {:receipt? true :expected-modes file-modes
                                                              :publication commit-publication}
                                                       required-subtree
                                                       (assoc :required-subtree required-subtree :removed-paths @removed
                                                              :subtree-basis @subtree-basis :stage-witness stage-witness))))
                                  entries (into {} (map (fn [p] [p (get tree (rel p))])) paths)]
                          ;; Only the forwarded physical write path can produce
                          ;; this receipt. A handler may answer without forwarding.
                              (vreset! applied (cond-> {:commit commit :entries entries :publication publication}
                                                 required-subtree (assoc :subtree required-subtree)))
                               ;; Durable publication already includes directory
                               ;; cleanup in its one joined transaction. Never
                               ;; enqueue another mutation after restoring an
                               ;; interrupted publication caller's interrupt flag.
                              (when (and (nil? publication) (nil? required-subtree) (seq @removed))
                                (guarded-native! completion true
                                                 #(d/transact conn [[:db.fn/call file-tx/remove-empty-dirs-tx
                                                                     (vec (distinct (mapcat file-tx/ancestors-of @removed)))]])))
                              ;; Ordinary cleanup must finish before a positive
                              ;; completion can escape. Durable publication has
                              ;; its directory cleanup in the joined transaction.
                              (observe-positive! completion (applied-result planned @applied message @removed))
                              commit)
                            (catch Throwable failure
                              ;; This contract never compensates: before native
                              ;; dispatch there is no owned mutation; afterward
                              ;; neither another writer's index nor a lost ACK
                              ;; can be safely undone by the legacy restore.
                              (let [e (if (and basis-write-state
                                               (#{:dispatched :written} @basis-write-state))
                                        (ex-info "Validated file transaction dispatched; inspect before retry"
                                                 {::publication/uncertain? true} failure)
                                        failure)]
                                (vreset! execution-error e)
                           ;; Native publication may have committed despite a lost
                           ;; acknowledgement. Never undo it or infer failure
                           ;; from an absent receipt; retain the host journal.
                                (if (or @applied (publication/uncertain? e)
                                        basis-write-state)
                                  (throw e)
                                  (try
                                    (let [_ (when (and expected-subtree
                                                       (or (.isInterrupted (Thread/currentThread)) (interrupted-failure? e)))
                                              (.interrupt (Thread/currentThread))
                                              (throw (ex-info "Conditional write interrupted; no cleanup was submitted"
                                                              {::publication/uncertain? true} e)))
                                          conflicted (guarded-native! completion true
                                                                      #(if expected-subtree
                                                                         (do (try (gs/assert-workspace-current! ws)
                                                                                  (catch Throwable changed
                                                                                    (throw (ex-info "Conditional cleanup target changed; no cleanup submitted"
                                                                                                    {::publication/uncertain? true} changed))))
                                                                             (publication/undo-subtree! conn @subtree-basis required-subtree
                                                                                                        (vec @touched) @stage-witness paths (vec created)))
                                                                         (restore! conn before expected @touched created)))]
                                      (throw (if (seq conflicted)
                                               (ex-info (str (ex-message e) "; not undone, changed meanwhile: "
                                                             (str/join ", " conflicted))
                                                        (assoc (ex-data e) :conflicted conflicted) e)
                                               e)))
                                    (catch Throwable final-error
                                   ;; Cleanup itself can have dispatched before
                                   ;; losing its ACK. An outer handler must not
                                   ;; hide that uncertainty by swallowing it.
                                      (when expected-subtree (vreset! execution-error final-error))
                                      (throw final-error))))))))
                _answer (try
                          (effects/perform!
                           outer-boundary {:effect :git/commit :resource {:message message :system-id system-id}}
                           #(force execute))
                          (catch Throwable error
                            (cond
                              (publication/uncertain? @execution-error) (throw @execution-error)
                              (and basis-write-state (#{:dispatched :written} @basis-write-state))
                              (throw (ex-info "Validated file dispatch completed before handler failure; inspect before retry"
                                              (cond-> {::publication/uncertain? true}
                                                @applied (assoc :applied @applied)) error))
                              (and publication @applied)
                              (throw (ex-info "Publication committed before completion failed"
                                              {::publication/uncertain? true
                                               :publication (:publication @applied)} error))
                              :else (throw error))))
                ;; An answering handler cannot turn an uncertain forwarded
                ;; write into an ordinary skipped operation by swallowing it.
                _ (when (publication/uncertain? @execution-error) (throw @execution-error))
                receipt @applied]
            (applied-result entries receipt message @removed)))))))

(defn write!
  "Write `files` (`{path content}`; content `{:text …}`, `{:base64 …}`, or nil
   to remove the file) into `room`'s workspace and commit exactly those paths
   as one commit with `message`. Ambient resolution happens once; files,
   staging, restore and commit use the same validated workspace descriptor.

   The batch is a `:git/commit` effect decided before anything is written;
   each write/removal is an effect too. Paths are checked first and the index
   must have nothing staged. Writes compare against the batch snapshot; on a
   prepublication failure its changes are restored unless changed meanwhile
   (reported as `:conflicted`). Returns `{:commit :applied? :message
   :written [{:path :size :content-id :entry}] :removed [path]}`. `:applied?`
   is true only when the physical executor ran, not an interceptor's answer;
   `:commit` is nil for an unchanged batch. Entries are immutable staged data.
   Optional `:file-modes` maps absolute batch paths to 33188 or 33261.

   Host-only `:publication` supplies a complete scoped receipt `:identity`,
   current `:authorize!` and durable `:before-dispatch!` hooks. Repeated ops
   recover before writing. Post-dispatch uncertainty NEVER restores files or
   authorizes automatic retry; retain the journal until scoped recovery.

   Optional `:expected-worktree` describes EVERY absolute batch path as an
   opened `{:content uuid :size n :mode n}` entry, or nil if absent. The SAME
   snapshot used by compare-and-write checks it before any effect/write. A
   stale opened version refuses the batch; omission retains unconditional
   client-write behavior.

   Host-only `:validated-basis` is file-tx/capture-basis from a fresh Save-time
   immutable DB used by the host for schema/constraint validation. Restricted
   to ONE existing regular-file replacement with expected-worktree, without
   subtree/publication. A fixed guard compares repository identity and the
   commit-id/max-tx pair atomically with content/worktree publication. It is
   not authority, schema validation, or a guarantee schemas stay unchanged
   afterward. Forwarding is synchronous and sealed on return. No compensation
   is attempted: native dispatch followed by failure is uncertain, while a
   proven native pre-write refusal leaves other writers' state untouched.

   Host-only `:expected-subtree` supplies a bounded full BEFORE prefix manifest;
   publication checks BOTH worktree and index against its derived AFTER tree.
   See `doc/subtree-cas.md` for bounds and cleanup/uncertainty semantics. Hosts
   requiring sealed completion evidence must use `write-to-completion!`;
   this option does not make intermediate writes invisible."
  [room files opts]
  (let [gate (when (contains? opts :validated-basis) (completion-gate))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (write-workspace! room (workspace! room) files opts gate))
      (finally (when gate (seal-completion! gate))))))

(defn write-to!
  "HOST API: write/commit a batch to this exact registered Geschichte system
   object in room's context, independent of its selected workspace. Same
   options, effects, permissions, receipts and recovery contract as write!.
   No selection mutation, Room facade or serializable descriptor capability."
  [room system files opts]
  (let [gate (when (contains? opts :validated-basis) (completion-gate))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (write-workspace! room (gs/workspace-for-system! system) files opts gate))
      (finally (when gate (seal-completion! gate))))))

(defn- interrupted-failure? [failure]
  ;; Native failure wrappers retain their causes. Do not infer cancellation
  ;; from interceptor-controlled ex-data, and tolerate a cyclic cause chain.
  (loop [cause failure seen #{}]
    (cond
      (nil? cause) false
      (instance? InterruptedException cause) true
      (contains? seen cause) false
      :else (recur (.getCause ^Throwable cause) (conj seen cause)))))

(defn write-to-completion!
  "HOST API: a closed native completion for this exact registered target.
   Returns {:completion :not-started|:returned|:pending :result … :error …}.
   Only :not-started proves this invocation entered no canonical native
   file/index/schema/publication mutation; use it only with the caller's
   separately authorized exact original claim, never an
   applying publication. :returned carries a native applied/recovered result,
   not an interceptor's answer. :pending does NOT certify compensation.

   Forwarding is synchronous on this calling worker thread only. A private
   bottom gate permanently closes before return; saved outer/inner next
   callbacks cannot start later writes. This is not a portable bearer token or
   a sandbox for arbitrary trusted-host handler I/O. Ordinary write!/write-to!
   retain their original contracts; :validated-basis opts into a sealed gate.
   Interruption remains set on this worker
   after closure. No new permission or executor is supplied."
  [room system files opts]
  (let [gate (completion-gate)
        result (volatile! nil)
        error (volatile! nil)
        closed (volatile! nil)]
    (try
      (when (simple/on-drain-thread?)
        (throw (ex-info "Native file completion requires a host worker"
                        {:type ::blocking-completion})))
      (binding [ec/*execution-context* (:ctx room)]
        (vreset! result (write-workspace! room (gs/workspace-for-system! system) files opts gate)))
      (catch Throwable failure (vreset! error failure))
      (finally (vreset! closed (seal-completion! gate))))
    ;; Normalize only AFTER permanent closure. Neither a forged response nor
    ;; Throwable metadata can supply the private positive/native-entry evidence.
    (when (interrupted-failure? @error)
      (.interrupt (Thread/currentThread)))
    (let [{:keys [active entered? positive]} @closed]
      {:completion (cond
                     (pos? active) :pending
                     positive :returned
                     (not entered?) :not-started
                     :else :pending)
       :result (or positive @result)
       :error @error})))
