(ns dvergr.personas
  "Pre-built `dvergr.discourse` Participants with curated prompts and tool sets.

   A persona is a role — researcher, coder, reviewer — packaged as a
   factory that returns an `llm-agent` Participant ready to `d/join`
   into a room or pass to `d/hire`/`dvergr.proposals/propose!`.

   Prompts live in `agents/*.md` (resources or working-directory
   relative). Personas are the lowest-friction way to spin up a
   capable agent in any discourse — no wiring, no enrichment, just a
   role with sensible defaults.

   Usage:

     (require '[dvergr.discourse :as d]
              '[dvergr.personas   :as personas])

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
            [clojure.string :as str]
            [dvergr.discourse.llm :as llm]))

;; ============================================================================
;; Prompt Loading
;; ============================================================================

(defn- load-prompt
  "Load `agents/<filename>` from classpath resource first, then from the
   current working directory. Throws if not found anywhere — callers
   should pass `:system-prompt` explicitly if they don't want the
   markdown lookup."
  [filename]
  (or (some-> (io/resource (str "agents/" filename)) slurp)
      (try (slurp (str "agents/" filename))
           (catch Exception _ nil))
      (try (slurp (str (System/getProperty "user.dir")
                       "/agents/" filename))
           (catch Exception _ nil))
      (throw (ex-info (str "Persona prompt not found: agents/" filename)
                      {:filename filename}))))

(defn- with-context-block
  "Append a small markdown block listing tool names + isolation hint, so
   the model knows what it has access to. Mirrors the legacy
   `build-system-prompt`."
  [base-prompt {:keys [tools isolation]}]
  (str base-prompt
       "\n\n## Available Tools\n\n"
       (when isolation
         (str "Isolation: " (name isolation) "\n"))
       (when (seq tools)
         (str "Tools: "
              (str/join ", " (map #(if (keyword? %) (name %) (str %)) tools))
              "\n"))))

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
  (let [tools     (or (:tools opts) default-tools)
        isolation (or (:isolation opts) default-isolation)
        prompt    (or (:system-prompt opts)
                      (with-context-block (load-prompt prompt-file)
                                          {:tools tools
                                           :isolation isolation}))]
    (llm/llm-agent
      (merge {:id    (or (:id opts) id)
              :spec  {:provider      (or (:provider opts) default-provider)
                      :model         (or (:model opts) default-model)
                      :system-prompt prompt}
              :tools tools}
             (select-keys opts [:db-conn :budget :compaction
                                :chat-ctx :tool-ctx
                                :run-turn-fn :ctx])))))

(defn researcher
  "Participant pre-configured for information gathering and analysis.

   Defaults:
     :id         :researcher
     :model      claude-sonnet-4-5 (Anthropic)
     :tools      read-only code-query tools + clojure_eval (for web
                 search/fetch via intake.web/*)
     :isolation  :native (trusted — no code mutation)
   Override any of these in `opts` (same shape as `llm-agent`)."
  ([] (researcher {}))
  ([opts]
   (build-persona {:id                :researcher
                   :prompt-file       "researcher.md"
                   :default-tools     #{:read-file :glob :grep
                                        :code-query :clojure-eval}
                   :default-isolation :native
                   :default-model     "claude-sonnet-4-5"
                   :default-provider  :anthropic}
                  opts)))

(defn coder
  "Participant pre-configured for implementation and testing.

   Defaults:
     :id         :coder
     :model      claude-sonnet-4-5 (Anthropic)
     :tools      read/write/edit + clojure_eval + shell + code_query + glob
     :isolation  :sci (sandboxed Clojure eval — safe by default)"
  ([] (coder {}))
  ([opts]
   (build-persona {:id                :coder
                   :prompt-file       "coder.md"
                   :default-tools     #{:read-file :write-file :edit-file
                                        :clojure-edit :shell :clojure-eval
                                        :code-query :glob}
                   :default-isolation :sci
                   :default-model     "claude-sonnet-4-5"
                   :default-provider  :anthropic}
                  opts)))

(defn reviewer
  "Participant pre-configured for code review and quality analysis.

   Defaults:
     :id         :reviewer
     :model      claude-sonnet-4-5 (Anthropic)
     :tools      read-only: read_file, glob, grep, code_query, shell
     :isolation  :native (trusted — no code mutation)"
  ([] (reviewer {}))
  ([opts]
   (build-persona {:id                :reviewer
                   :prompt-file       "reviewer.md"
                   :default-tools     #{:read-file :glob :grep
                                        :code-query :shell}
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
