(ns dvergr.discourse.definitions
  "Unified loader for file-driven *definitions* — skills AND agent identities —
   the source of truth for \"what an agent is and what it can do\".

   A definition is a YAML+markdown bundle: a frontmatter block followed by a
   body. `kind:` discriminates `:agent` (body = system prompt) from `:skill`
   (body = skill template). Both share one parser, one scope chain, one shape.

   Scope chain (later overrides earlier by `name`):

     builtin   resources/<kind>/                 (shipped, on classpath; jar-safe)
       → user      ~/.dvergr/<kind>/
       → project   ./.dvergr/<kind>/
       → room      <room-sandbox-repo>/<kind>/    (Phase 2 — per-room, agent-authorable)

   where <kind> ∈ {\"skills\", \"agents\"}. Files are the source of truth; a
   KB-derived index (Phase 3) is a queryable projection, never authoritative."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Frontmatter parser
;; ============================================================================

(defn- unquote-str
  "Strip a single pair of surrounding double/single quotes (YAML needs e.g. `\"*\"`
   quoted because bare `*` is an alias)."
  [s]
  (let [t (str/trim s)]
    (if (and (>= (count t) 2)
             (or (and (str/starts-with? t "\"") (str/ends-with? t "\""))
                 (and (str/starts-with? t "'") (str/ends-with? t "'"))))
      (subs t 1 (dec (count t)))
      t)))

(defn- parse-inline-value
  "Parse a single inline frontmatter value: bool, bracketed list `[a, b, c]`,
   or a (optionally quoted) bare string."
  [v]
  (let [t (str/trim v)]
    (cond
      (= t "true")  true
      (= t "false") false
      (= t "null")  nil
      (and (str/starts-with? t "[") (str/ends-with? t "]"))
      (->> (str/split (subs t 1 (dec (count t))) #",\s*")
           (map unquote-str)
           (remove empty?)
           vec)
      :else (unquote-str t))))

(defn- to-keyword [s]
  (if (keyword? s) s
      (-> s str str/trim
          (str/replace #"^:" "")
          keyword)))

;; Frontmatter keys whose values are capability/tool/room *lists* coerced to
;; vectors of keywords (provides/rooms) or strings (tools/requires-*).
(def ^:private keyword-list-keys #{:provides})
(def ^:private string-list-keys #{:requires-tools :tools :requires-env :rooms :triggers})

(defn parse-frontmatter
  "Parse YAML-ish frontmatter from a definition markdown file. Returns a map of
   the frontmatter fields plus `:content` (the body after the second `---`).
   List fields parse from `[a, b, c]` or indented `  - item` lines; `provides`
   coerces to keywords, the tool/env/room lists to strings, `kind` to a keyword."
  [text]
  (if-not (str/starts-with? text "---\n")
    {:content (str/trim text)}
    (let [end-idx (str/index-of text "\n---\n" 4)]
      (if-not end-idx
        {:content (str/trim text)}
        (let [fm-text (subs text 4 end-idx)
              content (subs text (+ end-idx 5))
              lines   (str/split-lines fm-text)
              {:keys [result]}
              (reduce
               (fn [{:keys [current-key result]} line]
                 (cond
                   (str/starts-with? line "  - ")
                   {:current-key current-key
                    :result      (update result current-key (fnil conj [])
                                         (unquote-str (subs line 4)))}

                   (re-matches #"^[a-zA-Z_-]+: .+" line)
                   (let [[k v] (str/split line #": " 2)
                         kw    (keyword (str/replace k "_" "-"))]
                     {:current-key kw
                      :result      (assoc result kw (parse-inline-value v))})

                   (re-matches #"^[a-zA-Z_-]+:$" line)
                   {:current-key (keyword (str/replace
                                           (subs line 0 (dec (count line)))
                                           "_" "-"))
                    :result      result}

                   :else
                   {:current-key current-key :result result}))
               {:current-key nil :result {}}
               lines)
              normalized
              (cond-> result
                (:kind result)     (update :kind to-keyword)
                true (as-> r (reduce (fn [m k]
                                       (if (contains? m k)
                                         (update m k #(mapv to-keyword (if (sequential? %) % [%])))
                                         m))
                                     r keyword-list-keys))
                true (as-> r (reduce (fn [m k]
                                       (if (contains? m k)
                                         (update m k #(if (sequential? %) % [%]))
                                         m))
                                     r string-list-keys)))]
          (assoc normalized :content (str/trim content)))))))

;; ============================================================================
;; File discovery (jar-safe builtin + filesystem user/project roots)
;; ============================================================================

(defn- list-md-files
  "All *.md files under a filesystem directory tree."
  [^java.io.File dir]
  (when (and dir (.isDirectory dir))
    (->> (file-seq dir)
         (filter #(and (.isFile ^java.io.File %)
                       (str/ends-with? (.getName ^java.io.File %) ".md")))
         vec)))

(defn- relative-name
  "Stable definition id from a filepath relative to its <kind> root.
   `discord.md` → \"discord\"; `lifted/summarize/SKILL.md` → \"lifted/summarize\"."
  [^java.io.File root ^java.io.File file]
  (-> (.getPath (.relativize (.toURI root) (.toURI file)))
      (str/replace #"\.md$" "")
      (str/replace #"/SKILL$" "")))

(defn- builtin-files
  "Built-in definitions under the `<kind>` classpath resource, as
   `[{:name :content :file :path}]`. Jar-safe (handles `file:` dev dirs and
   `jar:` uberjar entries — you cannot build a File from a jar entry)."
  [kind]
  (when-let [^java.net.URL res (io/resource kind)]
    (case (.getProtocol res)
      "file"
      (let [root (java.io.File. (.toURI res))]
        (mapv (fn [^java.io.File f]
                {:name (relative-name root f) :content (slurp f)
                 :file (.getName f) :path (.getPath f)})
              (list-md-files root)))
      "jar"
      (let [^java.net.JarURLConnection conn (.openConnection res)
            jar  (.getJarFile conn)
            pfx  (str kind "/")]
        (->> (enumeration-seq (.entries jar))
             (keep (fn [^java.util.jar.JarEntry e]
                     (let [n (.getName e)]
                       (when (and (str/starts-with? n pfx)
                                  (str/ends-with? n ".md")
                                  (not (.isDirectory e)))
                         {:name    (-> n (subs (count pfx))
                                       (str/replace #"\.md$" "")
                                       (str/replace #"/SKILL$" ""))
                          :content (slurp (io/resource n))
                          :file    (subs n (inc (.lastIndexOf n "/")))
                          :path    n}))))
             vec))
      [])))

(defn roots
  "USER + PROJECT directories for `<kind>` (real filesystem), increasing
   precedence — a later root's definition of the same name OVERRIDES earlier:

     1. user     — ~/.dvergr/<kind>/
     2. project   — ./.dvergr/<kind>/

   Built-ins (the `<kind>` classpath resource) sit BELOW these. Returns
   `[scope ^File]` pairs (existing dirs only), in precedence order."
  [kind]
  (->> [[:user    (io/file (System/getProperty "user.home") ".dvergr" kind)]
        [:project (io/file ".dvergr" kind)]]
       (filter (fn [[_ ^java.io.File f]] (and f (.isDirectory f))))
       vec))

(defn load-kind
  "Map of {name → parsed-definition} for `kind` (\"skills\"|\"agents\"), merged
   along the scope chain — later overrides earlier by name:

     builtin → user (~/.dvergr/<kind>) → project (./.dvergr/<kind>) → room

   The optional `room-dir` is a room's sandbox-repo working path; when given and
   `<room-dir>/<kind>/` exists, it is the highest-precedence (`:room`) layer —
   so a room can override or add its own definitions. `definitions` stays
   decoupled from rooms: the caller resolves the path (e.g. via
   `dvergr.substrate.git/current-worktree-path`).

   Each value includes the parsed frontmatter plus `:name :file :path :scope`
   and `:content` (the body)."
  ([kind] (load-kind kind nil))
  ([kind room-dir]
   (let [seed (reduce (fn [a {:keys [name content file path]}]
                        (let [parsed (parse-frontmatter content)]
                          (assoc a name (assoc parsed
                                               :name (or (:name parsed) name)
                                               :file file :path path :scope :builtin))))
                      {} (builtin-files kind))
         scopes (cond-> (roots kind)
                  (and room-dir (.isDirectory (io/file room-dir kind)))
                  (conj [:room (io/file room-dir kind)]))]
     (reduce
      (fn [acc [scope root]]
        (reduce
         (fn [a ^java.io.File f]
           (let [name'  (relative-name root f)
                 parsed (parse-frontmatter (slurp f))]
             (assoc a name'
                    (assoc parsed
                           :name (or (:name parsed) name')
                           :file (.getName f) :path (.getPath f) :scope scope))))
         acc (list-md-files root)))
      seed
      scopes))))

(defn load-one
  "The single parsed definition named `name` within `kind`, honoring the scope
   chain (room > project > user > builtin), or nil. Body is `:content`."
  ([kind name] (load-one kind name nil))
  ([kind name room-dir] (get (load-kind kind room-dir) name)))

(defn body
  "Just the body (system prompt / skill template) of definition `name` in
   `kind`, frontmatter stripped, or nil."
  ([kind name] (body kind name nil))
  ([kind name room-dir] (:content (load-one kind name room-dir))))

;; ============================================================================
;; Agent definition → daemon agent-config
;; ============================================================================

(defn- ->kw [x] (when x (keyword (if (keyword? x) (name x) (str x)))))

(defn agent->config
  "Map a parsed `:agent` definition (a value from `(load-kind \"agents\")`) to a
   daemon agent-config map (the shape `create-agent!` consumes). Frontmatter →
   config:

     name      → :id           (keyword)
     provider  → :provider     (keyword)
     model     → :model
     tools     → :tools        (tool-name strings)
     provides  → :tags + :skills  (capability tags → dispatch + behavior)
     rooms     → :rooms        (slugs to auto-join; `\"*\"` = all, expanded by caller)
     autostart/vetted/description carried through

   The body (system prompt) is NOT included — `dvergr.agent.persona/resolve-prompt`
   loads it from the file/DB by id at construction."
  [a]
  (cond-> {:id (->kw (:name a))}
    (:provider a)       (assoc :provider (->kw (:provider a)))
    (:model a)          (assoc :model (:model a))
    (seq (:tools a))    (assoc :tools (vec (:tools a)))
    (seq (:provides a)) (assoc :tags (set (:provides a)) :skills (set (:provides a)))
    (seq (:rooms a))    (assoc :rooms (vec (:rooms a)))
    true                (assoc :autostart (boolean (:autostart a))
                               :vetted (boolean (:vetted a))
                               :description (:description a))))

;; ============================================================================
;; Authoring + promotion (the vetting lifecycle)
;; ============================================================================

(defn- render-value [v]
  (cond
    (boolean? v)    (str v)
    (keyword? v)    (str ":" (name v))
    (sequential? v) (str "[" (str/join ", " (map render-value v)) "]")
    (and (string? v) (or (= v "*") (re-find #"[:#\[\]]" v))) (str "\"" v "\"")
    :else           (str v)))

(defn render-frontmatter
  "Render a frontmatter map back to a `---`-delimited YAML block (+ trailing
   newline). Internal load keys (:content/:file/:path/:scope) are dropped; a
   stable key order is used for readability, unknown keys appended."
  [m]
  (let [drop-ks #{:content :file :path :scope}
        order   [:kind :name :description :provides :tools :requires-tools
                 :requires-env :provider :model :autostart :rooms :skill-kind
                 :argument-hint :command :triggers :vetted :vetted-by :vetted-at :source]
        m       (apply dissoc m drop-ks)
        ks      (concat (filter #(contains? m %) order)
                        (remove (set order) (keys m)))]
    (str "---\n"
         (str/join "\n" (for [k ks]
                          (str (str/replace (name k) "-" "_") ": "
                               (render-value (get m k)))))
         "\n---\n")))

(defn author!
  "Write a definition file at `<dir>/<kind>/<name>.md` from `frontmatter` (a map)
   + `body`. `:kind`/`:name` are defaulted from the args. Agent-authored
   definitions default to `:vetted false` (+ the given `:source`) so the vetting
   gate keeps them out of prompts / autostart until promoted. `dir` is typically
   a room's sandbox-repo path. Returns the file path (string)."
  [kind dir name frontmatter body]
  (let [d (io/file dir kind)
        _ (.mkdirs d)
        f (io/file d (str name ".md"))
        fm (merge {:kind ({"agents" :agent "skills" :skill} kind (keyword kind))
                   :name name :vetted false}
                  frontmatter)]
    (spit f (str (render-frontmatter fm) "\n" (or body "")))
    (.getPath f)))

(defn promote!
  "Mark the definition file at `path` vetted — flip `vetted: false` → `true` and
   stamp `vetted_by: <by>` / `vetted_at: <date>` (adding the lines if absent).
   The reviewer action that lets an agent-authored or externally-lifted
   definition become eligible. `date` is an ISO yyyy-mm-dd string (pass it in;
   no clock is read here). Returns the new file text."
  [path by date]
  (let [text  (slurp path)
        text' (-> text
                  (str/replace #"(?m)^vetted:\s*false\s*$" "vetted: true")
                  (cond->
                   (not (re-find #"(?m)^vetted_by:" text))
                    (str/replace #"(?m)^(vetted:\s*true\s*)$"
                                 (str "$1\nvetted_by: " by "\nvetted_at: " date))))]
    (spit path text')
    text'))

(defn autostart-agents
  "All `:agent` definitions flagged `autostart: true` AND `vetted: true`, as
   `[id agent-config]` pairs ready for `create-agent!`. Optional `room-dir`
   includes a room's own agent definitions. The vetting gate keeps unreviewed /
   externally-lifted agent files from booting."
  ([] (autostart-agents nil))
  ([room-dir]
   (->> (load-kind "agents" room-dir)
        vals
        (filter #(and (true? (:autostart %)) (:vetted %)))
        (map (fn [a] (let [c (agent->config a)] [(:id c) c]))))))
