(ns dvergr.orchestration.skills
  "Skill registry + dispatch.

   A skill is a YAML+markdown bundle under resources/skills/*.md. The
   frontmatter declares:

     name           — human/agent identifier (string)
     description    — one-liner shown in UI / agent system prompt
     provides       — capability tags (keywords) this skill provides
     requires_tools — tool names that must be available (existing field)
     requires_env   — env keys that must be set (existing field)
     vetted         — has a maintainer reviewed this for safety?
     vetted_at      — review date (yyyy-mm-dd) when vetted: true
     vetted_by      — reviewer's handle when vetted: true
     source         — provenance (e.g. \"openclaw\", \"dvergr\", \"user\")
     triggers       — phrases that should activate this skill (optional)

   Skills are loaded from disk on demand and cached. The body (markdown
   after frontmatter) is what gets appended to an agent's system prompt
   when the skill is enabled for them.

   Dispatch:
     `find-providers` returns actor-ids whose `:actor/skills` contains
     the requested tag. `dispatch-target` ranks them by composite
     priority:
       1. filter to :status :online
       2. rank by per-actor :skill-priorities override, else kind default
          (agent=100, external=50, human=10, service=0)
       3. tiebreak by :created-at (older wins — established providers
          are preferred over fresh spawns)

   Phase C exposes the dispatch surface to SCI as the 'skills namespace
   and refactors the daemon's inline `load-skills`/`inject-skills` to
   live here."
  (:require [clojure.set]
            [clojure.string :as str]
            [dvergr.actors :as actors]
            [dvergr.discourse.definitions :as defs]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Kind defaults
;; ============================================================================

(def kind-default-priority
  "Implicit baseline priority by actor :kind, used when the actor
   has no per-skill override in :skill-priorities. Lower kinds yield
   to higher ones at equal explicit priority."
  {:agent    100
   :external 50
   :human    10
   :service  0})

;; ============================================================================
;; Frontmatter parser + loading — delegated to dvergr.discourse.definitions,
;; the shared file-driven loader for BOTH skills and agent identities. These
;; thin aliases keep the historical `skills/*` API while the parsing + scope
;; chain (builtin → user → project, jar-safe) live in one place.
;; ============================================================================

(def parse-skill-frontmatter
  "Parse YAML+markdown frontmatter (alias for `definitions/parse-frontmatter`)."
  defs/parse-frontmatter)

(defn skill-roots
  "USER + PROJECT skill dirs (`~/.dvergr/skills`, `./.dvergr/skills`), in
   precedence order — see `definitions/roots`."
  []
  (defs/roots "skills"))

(defn load-all
  "Map of {skill-name → parsed-skill-map}, merged room > project > user >
   built-in. See `definitions/load-kind`. Optional `room-dir` (a room's
   sandbox-repo path) adds the room's `skills/` as the highest-precedence
   layer. Each value includes:
     :name :description :provides :requires-tools :requires-env
     :vetted :vetted-at :vetted-by :source :content :file :scope"
  ([] (defs/load-kind "skills"))
  ([room-dir] (defs/load-kind "skills" room-dir)))

(defn list-skills
  "Sequence of parsed skills, optionally filtered by :vetted, :provides,
   :source."
  [& {:keys [vetted provides source]}]
  (->> (load-all)
       vals
       (filter (fn [s]
                 (and (or (nil? vetted)
                          (= vetted (boolean (:vetted s))))
                      (or (nil? provides)
                          (some #(= provides %) (:provides s)))
                      (or (nil? source)
                          (= source (:source s))))))))

;; ============================================================================
;; Eligibility (vetting + tools + env)
;; ============================================================================

(defn eligible?
  "Is the skill safe + loadable to inject for an agent with these tools? Eligible
   iff it is **vetted** AND (set requires_tools) ⊆ (set available-tool-names) AND
   every requires_env key is present in System/getenv.

   The vetting gate keeps unreviewed / externally-
   lifted skills (`vetted: false`, e.g. `lifted/*`) out of agent prompts until a
   human or trusted reviewer promotes them — they stay loadable/listable, just
   not auto-injected."
  [skill available-tool-names]
  (let [needed-tools (set (or (:requires-tools skill) []))
        missing-tools (clojure.set/difference needed-tools
                                              (set (map name available-tool-names)))
        needed-env   (set (or (:requires-env skill) []))
        missing-env  (->> needed-env
                          (remove #(System/getenv (name %)))
                          set)]
    (and (:vetted skill) (empty? missing-tools) (empty? missing-env))))

(defn eligible-skills
  "All skills eligible for the given tool set, sorted by name. Optional
   `room-dir` adds the room's own skills (highest precedence)."
  ([available-tool-names] (eligible-skills available-tool-names nil))
  ([available-tool-names room-dir]
   (->> (load-all room-dir)
        vals
        (filter #(eligible? % available-tool-names))
        (sort-by :name))))

;; ============================================================================
;; System-prompt injection
;; ============================================================================

(defn read-skill
  "Full markdown body of a skill by name (progressive disclosure — the agent
   pulls this on demand after seeing the brief index). nil if unknown. Optional
   `room-dir` consults the room's own skills first."
  ([skill-name] (read-skill skill-name nil))
  ([skill-name room-dir] (:content (get (load-all room-dir) (str skill-name)))))

(defn skill-prompt-section
  "Build the `## Skills` block appended to an agent's system prompt. Progressive
   disclosure: inject only a BRIEF index (name — description) so the prompt stays
   small as the library grows; the agent reads the full instructions on demand
   with `(skills/read \"<name>\")`. Skills with `always: true` inject their full
   body (for short, always-on skills). Returns nil if empty."
  [skills]
  (when (seq skills)
    (let [always (filter :always skills)
          brief  (remove :always skills)]
      (str "\n\n---\n\n## Skills\n\n"
           "You have skills available. Each is a short instruction set; read the "
           "FULL text before using one with `(skills/read \"<name>\")` in "
           "clojure_eval.\n\n"
           (str/join "\n"
                     (for [s brief]
                       (str "- **" (:name s) "** — " (:description s)
                            (when-let [h (:argument-hint s)] (str "  `" h "`")))))
           (when (seq always)
             (str "\n\n" (str/join "\n\n---\n\n" (map :content always))))))))

(defn inject-skills
  "Append the eligible-skill index to a system prompt. Optional `room-dir`
   includes the room's own skills (highest precedence) so an agent acting in a
   room sees skills that room defines. Phase B/C bridge — keeps the tool-driven
   eligibility filter; later phases also gate on `:vetted` and per-actor skills."
  ([system-prompt available-tool-names] (inject-skills system-prompt available-tool-names nil))
  ([system-prompt available-tool-names room-dir]
   (let [eligible (eligible-skills available-tool-names room-dir)]
     (if-let [section (skill-prompt-section eligible)]
       (do
         (doseq [s (->> (load-all room-dir) vals (remove #(eligible? % available-tool-names)))]
           (tel/log! {:id :skills/ineligible
                      :data {:file (:file s)
                             :missing (clojure.set/difference
                                       (set (:requires-tools s))
                                       (set (map name available-tool-names)))}}
                     "Skill not loaded — tools unavailable"))
         (str system-prompt section))
       system-prompt))))

;; ============================================================================
;; Provider lookup + composite-priority ranking
;; ============================================================================

(defn priority-for
  "Effective priority of `actor` for `skill`. Per-skill override beats
   the kind default. Returns 0 if the actor doesn't provide the skill
   (caller usually filters those out first)."
  [actor skill]
  (let [overrides (:skill-priorities actor)]
    (or (get overrides skill)
        (get kind-default-priority (:kind actor) 0))))

(defn find-providers
  "Actor ids that declare `skill` in their :actor/skills set,
   regardless of status. Use `dispatch-target` for the policy-aware
   pick."
  [conn skill]
  (mapv :id (actors/list-actors conn :skill skill)))

(defn rank-providers
  "Return the actors that can provide `skill`, sorted by composite
   priority (online first, then priority desc, then created-at asc).
   Returns the full actor records (not just ids)."
  [conn skill]
  (->> (actors/list-actors conn :skill skill)
       (filter #(= :online (:status %)))
       (sort (fn [a b]
               (let [pa (priority-for a skill)
                     pb (priority-for b skill)]
                 (cond
                   (not= pa pb)   (compare pb pa)             ; priority desc
                   :else          (compare (:created-at a)    ; oldest first
                                           (:created-at b))))))
       vec))

(defn dispatch-target
  "Pick the single best provider for `skill`. Returns the actor record
   or nil if no provider exists. Pair with `dispatch!` to actually
   invoke; `dispatch-target` is for lookup-only callers."
  [conn skill]
  (first (rank-providers conn skill)))

;; ============================================================================
;; Dispatch (invocation) — delegates to dvergr.actors.transport
;; ============================================================================

(defn dispatch!
  "Pick the best actor for `skill` and route the task through the
   transport registered for that actor's kind. Returns a uniform
   result map:

     {:actor <actor-map> :status :dispatched :task <task-or-nil>}
     {:status :no-provider}
     {:status :unsupported :actor <actor-map> :error <string>}
     {:status :error      :error <string>}

   Required: :task (string description of the work)
   Optional: :room-id (defaults to :boardroom)
             :from-actor :id of the dispatching actor (or nil if user)

   Per-kind behavior is defined by the transports registered with
   `dvergr.actors.transport/register-transport!`. dvergr ships
   default :agent and :human transports; :external and :service are
   embedder-supplied (e.g. simmis registers an MCP transport)."
  [conn skill {:keys [task] :as opts}]
  (require 'dvergr.actors.transport)
  (let [dispatch-fn @(ns-resolve 'dvergr.actors.transport 'dispatch)]
    (if-not task
      {:status :error :error ":task is required for dispatch!"}
      (let [actor (dispatch-target conn skill)]
        (dispatch-fn conn actor (assoc opts :skill skill))))))
