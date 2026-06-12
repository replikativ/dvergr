(ns dvergr.discourse.personas
  "Pre-built `dvergr.discourse` Participants with curated prompts and tool sets.

   A persona is a role — researcher, coder, reviewer — packaged as a
   factory that returns an `llm-agent` Participant ready to `d/join`
   into a room or pass to `d/hire`.

   Prompts live in `agents/*.md` (resources or working-directory
   relative). Personas are the lowest-friction way to spin up a
   capable agent in any discourse — no wiring, no enrichment, just a
   role with sensible defaults.

   Usage:

     (require '[dvergr.discourse :as d]
              '[dvergr.discourse.personas   :as personas])

     ;; Drop a researcher into an existing room
     (binding [ec/*execution-context* (:ctx room)]
       (d/join room (personas/researcher)))

     ;; Hire a coder for a one-shot job
     (d/hire room (personas/coder {:id :impl}) {:goal \"implement X\"})

   Each factory accepts overrides matching `llm-agent` options
   (`:id :model :provider :tools :budget :chat-ctx :ctx :run-turn-fn`)
   plus a `:tools` shortcut for the persona's default tool set.

   Note: `:tools` here is the set of tool *names* (keywords or strings)
   passed through to `llm-agent`, which resolves them against
   `dvergr.tools/registry`. Tool sets are conservative by default —
   override when you need broader access."
  (:require [clojure.java.io :as io]
            [dvergr.tools :as tools]
            [dvergr.agent.prompt :as prompt]
            [dvergr.discourse.definitions :as defs]
            [dvergr.model.providers :as providers]
            [dvergr.discourse.llm :as llm]))

;; ============================================================================
;; Prompt Loading
;; ============================================================================

(defn- load-prompt
  "Load the system-prompt BODY of `agents/<filename>` from classpath resource
   first, then the current working directory. Frontmatter (if any) is stripped —
   only the markdown body becomes the prompt. Throws if not found anywhere —
   callers should pass `:system-prompt` explicitly to skip the markdown lookup."
  [filename]
  (let [raw (or (some-> (io/resource (str "agents/" filename)) slurp)
                (try (slurp (str "agents/" filename))
                     (catch Exception _ nil))
                (try (slurp (str (System/getProperty "user.dir")
                                 "/agents/" filename))
                     (catch Exception _ nil))
                (throw (ex-info (str "Persona prompt not found: agents/" filename)
                                {:filename filename})))]
    (:content (defs/parse-frontmatter raw))))

;; ============================================================================
;; Persona Factories
;;
;; Each returns an `llm-agent` Participant. The persona's identity
;; (prompt + default tools + isolation hint) is data; the rest is just
;; passthrough to llm-agent.
;; ============================================================================

(defn- build-persona
  [{:keys [id prompt-file default-tools default-isolation default-model
           default-provider]}
   opts]
  (let [raw-tools (or (:tools opts) default-tools)
        ;; The contract on llm-agent's :tools is "set/vector of names OR map of
        ;; name → tool-def". Normalize via the registry's own helper so both
        ;; forms work as documented (and the registry stays the single owner of
        ;; that logic).
        tools     (tools/normalize-tools raw-tools)
        isolation (or (:isolation opts) default-isolation)
        ;; ALWAYS run the base prompt (caller :system-prompt OR the persona file)
        ;; through the ONE shared assembler — so personas get the SAME treatment as
        ;; daemon agents: discourse preamble + skills + tool-use guideline + sandbox
        ;; pointer. Never skipped for a custom :system-prompt. (Bug it fixed: the
        ;; playground passes :system-prompt, which used to drop the steering →
        ;; agents never learned they could fetch the web via clojure_eval.)
        sys-prompt (prompt/assemble-system-prompt
                    (or (:system-prompt opts) (load-prompt prompt-file))
                    {:tools tools :isolation isolation})
        ;; Resolve provider/model: explicit opts win, then the system's env-aware
        ;; auto-config (Anthropic where ANTHROPIC_API_KEY is set, Fireworks where
        ;; it isn't), then the persona's hardcoded fallback. Keeps personas from
        ;; pinning a provider that may not be available on this machine.
        def-spec  (providers/default-spec)]
    (llm/llm-agent
     (merge {:id    (or (:id opts) id)
             :spec  {:provider      (or (:provider opts) (:provider def-spec) default-provider)
                     :model         (or (:model opts) (:model def-spec) default-model)
                     :system-prompt sys-prompt}
             :tools tools}
            (select-keys opts [:db-conn :budget :compaction
                               :chat-ctx :tool-ctx
                               :run-turn-fn :ctx])))))

(defn researcher
  "Participant pre-configured for information gathering and analysis.

   Defaults:
     :id         :researcher
     :model      auto-detected from env (providers/default-spec)
     :tools      minimal-readonly: read_file, shell, clojure_eval — search via
                 shell (grep/rg/find); web/data via clojure_eval (intake.web, dh/q)
     :isolation  :native (trusted — no code mutation)
   Override any of these in `opts` (same shape as `llm-agent`)."
  ([] (researcher {}))
  ([opts]
   (build-persona {:id                :researcher
                   :prompt-file       "researcher.md"
                   :default-tools     tools/minimal-readonly-tools
                   :default-isolation :native
                   :default-model     "claude-sonnet-4-5"
                   :default-provider  :anthropic}
                  opts)))

(defn coder
  "Participant pre-configured for implementation and testing.

   Defaults:
     :id         :coder
     :model      auto-detected from env (providers/default-spec)
     :tools      minimal-coding: read/write/edit + clojure_edit + shell +
                 clojure_eval (the escape hatch for web/data/code-query)
     :isolation  :sci (sandboxed Clojure eval — safe by default)"
  ([] (coder {}))
  ([opts]
   (build-persona {:id                :coder
                   :prompt-file       "coder.md"
                   :default-tools     tools/minimal-coding-tools
                   :default-isolation :sci
                   :default-model     "claude-sonnet-4-5"
                   :default-provider  :anthropic}
                  opts)))

(defn reviewer
  "Participant pre-configured for code review and quality analysis.

   Defaults:
     :id         :reviewer
     :model      auto-detected from env (providers/default-spec)
     :tools      minimal-readonly: read_file, shell, clojure_eval
     :isolation  :native (trusted — no code mutation)"
  ([] (reviewer {}))
  ([opts]
   (build-persona {:id                :reviewer
                   :prompt-file       "reviewer.md"
                   :default-tools     tools/minimal-readonly-tools
                   :default-isolation :native
                   :default-model     "claude-sonnet-4-5"
                   :default-provider  :anthropic}
                  opts)))

;; ============================================================================
;; Custom personas from a prompt file
;; ============================================================================

(defn from-prompt
  "Build a Participant from a custom markdown prompt at agents/<filename>.

   Use this for project-specific personas without baking them into this
   ns. `opts` is the `llm-agent` option map plus `:tools` and
   `:isolation` for the context block.

     (personas/from-prompt \"domain-expert.md\"
                           {:id :expert :tools #{:read-file :code-query}})"
  [prompt-filename opts]
  (build-persona {:id                (or (:id opts) :custom)
                  :prompt-file       prompt-filename
                  :default-tools     (or (:tools opts) :all)
                  :default-isolation (or (:isolation opts) :native)
                  :default-model     "claude-sonnet-4-5"
                  :default-provider  :anthropic}
                 opts))
