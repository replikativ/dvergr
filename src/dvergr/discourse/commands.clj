(ns dvergr.discourse.commands
  "Unified slash-command registry — ONE surface every frontend (TUI, web)
   dispatches through.

   A command is DATA + a run fn, so the same registry serves four sources:

     :builtin  — shipped handlers (/fork /worktree /model /plan /help /skill)
     :prompt   — a markdown template (frontmatter `kind: prompt`); `/name args`
                 expands $1/$ARGUMENTS/$(clojure) and is posted as a USER message
                 (triggers a turn). The opencode/pi prompt-template pattern.
     :handler  — a markdown body of Clojure (frontmatter `kind: handler`),
                 eval'd in the room's SCI sandbox. Must be `vetted: true`.
     :skill    — every eligible skill is also exposed as `/skill:<name>`.

   Because a command is just a value with a `:run` fn, a running agent can
   register one from inside `clojure_eval` via `register!` — self-extension
   without a plugin loader.

   ## Host contract (the `ctx` passed to `execute!` / a command's `:run`)

   The frontend supplies capabilities so this namespace stays free of TUI/daemon
   deps:

     :argv         parsed argument vector (after the command name)
     :args-str     raw argument string (everything after the name)
     :room         the current Room (may be nil)
     :agent-id     the focused agent keyword (may be nil)
     :daemon       the daemon map (for built-ins that need it)
     :exec-ctx     the spindel execution context
     :available-tools  tool names available here (for skill eligibility)
     :post-user!   (fn [text]) — post `text` as a user message → triggers a turn
     :notify!      (fn [text]) — post `text` as a no-turn activity/system line
     :switch-room! (fn [room])  — focus a (possibly new) room in the frontend
     :set-model!   (fn [model]) — set the model for the current agent/room (opt)
     :get-model    (fn [])      — current model string (opt)
     :sci-eval     (fn [code])  — eval Clojure in the room's sandbox (opt)

   `execute!` returns {:handled? bool :no-turn? bool :reply <string-or-nil>}."
  (:require [clojure.string :as str]
            [dvergr.orchestration.skills :as skills]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce ^:private builtins (atom {}))

(defn register!
  "Register/replace a built-in command. `m` needs at least :name + :run (or
   :template for a :prompt). Callable from SCI for agent self-extension."
  [m]
  (let [nm (str (:name m))]
    (swap! builtins assoc nm (assoc m :name nm :kind (or (:kind m) :builtin)))
    nm))

(defn unregister! [nm] (swap! builtins dissoc (str nm)) nil)

(defn match-builtins
  "Built-in command names whose name starts with the prefix the user is typing —
   for the TUI's `/`-hint. Cheap (built-ins only, no skill scan); returns nil
   once an argument is started (a space after the name)."
  [text]
  (let [t (str/triml (str text))]
    (when (and (str/starts-with? t "/") (not (re-find #"\s" t)))
      (let [prefix (str/lower-case (subs t 1))]
        (->> (keys @builtins)
             (filter #(str/starts-with? (str/lower-case %) prefix))
             sort vec)))))

;; ============================================================================
;; Input parsing
;; ============================================================================

(defn command-input?
  "True if `text` looks like a slash command (a leading `/` then a name char)."
  [text]
  (boolean (and text (re-find #"^/[a-zA-Z]" (str/trimr (str/triml text))))))

(defn parse-argv
  "Bash-ish split honoring single/double quotes. `a \"b c\" 'd'` → [a \"b c\" d]."
  [s]
  (->> (re-seq #"\"([^\"]*)\"|'([^']*)'|(\S+)" (or s ""))
       (map (fn [[_ dq sq bare]] (or dq sq bare)))
       (remove nil?)
       vec))

(defn parse-input
  "Split `/name rest...` → {:name \"name\" :args-str \"rest...\" :argv [...]}.
   Returns nil if `text` isn't a command. The name may carry a `:suffix`
   (`/skill:linear` → name \"skill\", suffix \"linear\")."
  [text]
  (when (command-input? text)
    (let [t       (str/trim text)
          body    (subs t 1)                       ; drop leading /
          [head & _] (str/split body #"\s+" 2)
          args-str (str/trim (subs body (min (count body) (count head))))
          [nm suffix] (str/split head #":" 2)]
      {:name     nm
       :suffix   suffix
       :args-str args-str
       :argv     (parse-argv args-str)})))

;; ============================================================================
;; Template expansion ($1 $2 $@ $ARGUMENTS ${@:N} ${@:N:L} + inline $(clojure))
;; ============================================================================

(defn expand-template
  "Substitute argument placeholders (and, if `sci-eval` is supplied, inline
   `$(clojure-expr)`) in a prompt template.

     $1 $2 …        positional (1-indexed); missing → \"\"
     $@ $ARGUMENTS  all args joined by spaces
     ${@:N}         args from position N (1-indexed) onward
     ${@:N:L}       L args starting at position N
     $(fn args)     eval the Clojure call `(fn args)` in the sandbox, splice its
                    printed value — e.g. $(bash/run \"ls\"), $(git/status)"
  ([template argv] (expand-template template argv nil))
  ([template argv sci-eval]
   (let [argv    (vec argv)
         joined  (str/join " " argv)
         at      (fn [n] (or (get argv (dec n)) ""))
         slice   (fn [n l] (str/join " " (cond->> (drop (dec n) argv)
                                           l (take l))))
         step1   (-> template
                     ;; ${@:N:L} and ${@:N}
                     (str/replace #"\$\{@:(\d+):(\d+)\}"
                                  (fn [[_ n l]] (slice (parse-long n) (parse-long l))))
                     (str/replace #"\$\{@:(\d+)\}"
                                  (fn [[_ n]] (slice (parse-long n) nil)))
                     (str/replace #"\$ARGUMENTS" joined)
                     (str/replace #"\$@" joined)
                     (str/replace #"\$(\d+)"
                                  (fn [[_ n]] (at (parse-long n)))))]
     (if sci-eval
       (str/replace step1 #"\$\((.*?)\)"
                    (fn [[_ expr]]
                      ;; $(fn args) → eval the call (fn args); the parens you
                      ;; write are the form, like shell $(...).
                      (try (str (sci-eval (str "(" expr ")")))
                           (catch Throwable e (str "«error: " (.getMessage e) "»")))))
       step1))))

;; ============================================================================
;; Assembling the live command list
;; ============================================================================

(defn- file-commands
  "Skills whose frontmatter opts them into the slash surface — `kind: prompt`
   or `kind: handler`, or an explicit `command:` name. Returned as command maps."
  [available-tools]
  (->> (skills/eligible-skills available-tools)
       (keep (fn [s]
               (let [kind (some-> (:kind s) name keyword)]
                 (when (or (#{:prompt :handler} kind) (:command s))
                   {:name          (str (or (:command s) (:name s)))
                    :description   (:description s)
                    :kind          (or kind :prompt)
                    :argument-hint (:argument-hint s)
                    :agent         (some-> (:agent s) name keyword)
                    :isolation     (some-> (:isolation s) name keyword)
                    :vetted        (boolean (:vetted s))
                    :template      (:content s)}))))))

(defn- skill-commands
  "Every eligible skill as `/skill:<name>` (codex/pi pattern)."
  [available-tools]
  (->> (skills/eligible-skills available-tools)
       (map (fn [s]
              {:name          (str "skill:" (:name s))
               :description   (str "skill — " (:description s))
               :kind          :skill
               :argument-hint "[task]"
               :skill         s}))))

(defn all-commands
  "The merged, deduped command list visible in `ctx` (built-ins win on name)."
  [{:keys [available-tools] :as _ctx}]
  (let [bs (vals @builtins)
        fc (file-commands available-tools)
        sk (skill-commands available-tools)
        by-name (reduce (fn [m c] (assoc m (:name c) c))
                        {} (concat sk fc bs))]   ; builtins last → win
    (->> (vals by-name) (sort-by :name) vec)))

(defn lookup
  "Resolve a parsed input's command. `/skill:linear` resolves the `skill`
   built-in (suffix carried in ctx); otherwise an exact name match."
  [{:keys [name suffix]} ctx]
  (let [cmds (into {} (map (juxt :name identity) (all-commands ctx)))]
    (cond
      (and (= name "skill") suffix) (get cmds (str "skill:" suffix))
      :else (or (get cmds name)
                (when suffix (get cmds (str name ":" suffix)))))))

;; ============================================================================
;; Execution
;; ============================================================================

(defn- run-prompt!
  "A :prompt command — expand the template and post as a user message."
  [{:keys [template]} {:keys [argv sci-eval post-user!] :as _ctx}]
  (let [expanded (expand-template template argv sci-eval)]
    (when post-user! (post-user! expanded))
    {:handled? true :no-turn? false}))

(defn- run-handler!
  "A :handler command — eval its Clojure body in the room sandbox. Vetted only."
  [{:keys [template vetted name]} {:keys [args-str sci-eval notify!] :as _ctx}]
  (cond
    (not vetted)
    (do (when notify! (notify! (str "Command /" name " is not vetted — refusing to run its code.")))
        {:handled? true :no-turn? true})
    (not sci-eval)
    {:handled? true :no-turn? true :reply "No sandbox available here."}
    :else
    (let [code (str "(let [args " (pr-str args-str) "] " template "\n)")
          out  (try (str (sci-eval code))
                    (catch Throwable e (str "Error: " (.getMessage e))))]
      (when notify! (notify! out))
      {:handled? true :no-turn? true :reply out})))

(defn- run-skill!
  "A /skill:<name> command — post the skill body (+ any args) as a user turn."
  [{:keys [skill]} {:keys [args-str post-user!] :as _ctx}]
  (let [body (str "Use the **" (:name skill) "** skill.\n\n"
                  (:content skill)
                  (when (seq args-str) (str "\n\n---\n\nTask: " args-str)))]
    (when post-user! (post-user! body))
    {:handled? true :no-turn? false}))

(defn execute!
  "Parse `text`, resolve the command, run it with host `ctx`. Returns
   {:handled? bool :no-turn? bool :reply <string-or-nil>}; :handled? false means
   `text` was not a command (the caller should post it normally)."
  [text ctx]
  (if-let [parsed (parse-input text)]
    (let [cmd  (lookup parsed ctx)
          ctx* (merge ctx parsed)]
      (cond
        (nil? cmd)
        {:handled? true :no-turn? true
         :reply (str "Unknown command /" (:name parsed)
                     ". Try /help.")}

        (:run cmd)        ((:run cmd) ctx*)         ; :builtin
        (= :prompt  (:kind cmd)) (run-prompt!  cmd ctx*)
        (= :handler (:kind cmd)) (run-handler! cmd ctx*)
        (= :skill   (:kind cmd)) (run-skill!   cmd ctx*)
        :else {:handled? true :no-turn? true :reply "Command has no action."}))
    {:handled? false}))

;; ============================================================================
;; Built-in commands
;; ============================================================================

(defn- fmt-command-list [cmds]
  (->> cmds
       (map (fn [{:keys [name description argument-hint]}]
              (str "  /" name (when argument-hint (str " " argument-hint))
                   (when description (str "  — " description)))))
       (str/join "\n")))

(register!
 {:name "help"
  :description "List available slash commands"
  :argument-hint nil
  :run (fn [ctx]
         (let [body (str "Available commands:\n\n" (fmt-command-list (all-commands ctx)))]
           (when-let [n (:notify! ctx)] (n body))
           {:handled? true :no-turn? true :reply body}))})

(register!
 {:name "commands" :description "Alias of /help" :argument-hint nil
  :run (fn [ctx] ((:run (get @builtins "help")) ctx))})

;; Per-[room,agent] model override set by /model. The daemon reads this at turn
;; time (commands/model-override), falling back to the agent's configured model.
(defonce model-overrides (atom {}))

(defn model-override [room-id agent-id] (get @model-overrides [room-id agent-id]))

(register!
 {:name "model"
  :description "Show or set this agent's model in this room"
  :argument-hint "[model-id]"
  :run (fn [{:keys [argv room agent-id notify!]}]
         (require 'dvergr.model.registry)
         (let [get-model   (ns-resolve 'dvergr.model.registry 'get-model)
               list-models (ns-resolve 'dvergr.model.registry 'list-models)
               target      (first argv)
               k           [(:id room) agent-id]
               ids         (delay (sort (map :id (list-models))))
               say         (fn [m] (when notify! (notify! m))
                             {:handled? true :no-turn? true})]
           (cond
             (nil? target)
             (say (str "Model: " (or (get @model-overrides k) "(agent default)")
                       "\nAvailable: " (str/join ", " @ids)
                       "\nSet with /model <id>."))
             (get-model target)
             (do (swap! model-overrides assoc k target)
                 (say (str "Model → " target " (this room; next turn).")))
             :else
             (say (str "Unknown model '" target "'.\nAvailable: "
                       (str/join ", " @ids))))))})

(defn- do-fork!
  "Fork the current room with `:isolation :ctx` — a real branch: the fork gets
   its OWN forked execution context (git worktree + Datahike branch) seeded with
   the parent's history, so speculative work is isolated and can later be MERGED
   back into the parent (or discarded). The fork is registered in the parent ctx
   so the tree shows it; navigate in, work, then merge/discard from the tree."
  [{:keys [room switch-room! notify!]}]
  (require 'dvergr.discourse)
  (if-not room
    (do (when notify! (notify! "No room to fork here."))
        {:handled? true :no-turn? true})
    (let [fork-room (ns-resolve 'dvergr.discourse 'fork-room)
          forked    (fork-room room {:isolation :ctx})]
      (when (and switch-room! forked) (switch-room! forked))
      (when notify! (notify! (str "Forked → " (:slug forked)
                                  " (isolated branch). Merge or discard it from the tree.")))
      {:handled? true :no-turn? true})))

(register!
 {:name "fork"
  :description "Fork this conversation into a new room (inherits history) + switch"
  :argument-hint "[label]"
  :run do-fork!})

(register!
 {:name "worktree"
  :description "Worktree-isolated fork (git branch) — not yet UI-navigable"
  :argument-hint nil
  :run (fn [{:keys [notify!]}]
         (when notify!
           (notify! (str "Worktree-isolated forks run in a separate execution "
                         "context the UI can't display yet — use /fork for a "
                         "conversational fork. (Tracked in doc/command-systems.md.)")))
         {:handled? true :no-turn? true})})

(register!
 {:name "invite"
  :description "Add an agent to this room (so you can chat with it here)"
  :argument-hint "<agent-id>"
  :run (fn [{:keys [argv room daemon notify! select-agent!]}]
         (require 'dvergr.orchestration.daemon)
         (let [aid (some-> (first argv) str/trim not-empty keyword)
               say (fn [m] (when notify! (notify! m)) {:handled? true :no-turn? true})]
           (cond
             (nil? aid)    (say "Usage: /invite <agent-id> — e.g. /invite var")
             (nil? room)   (say "No room here to invite into.")
             (nil? daemon) (say "Inviting isn't available here.")
             :else
              ;; actor-row-first (durable rows, incl. runtime-created agents) with
              ;; a fallback to the boot config — same rule the daemon's room-join
              ;; path uses, so any real agent is invitable, not just config ones.
             (let [join-cfg (ns-resolve 'dvergr.orchestration.daemon 'agent-join-config)
                   cfg      (join-cfg daemon aid)]
               (if-not cfg
                 (say (str "Unknown agent '" (name aid) "'."))
                 (let [resolve-safe (ns-resolve 'dvergr.orchestration.daemon 'resolve-safe-config)
                       join!        (ns-resolve 'dvergr.orchestration.daemon 'join-agent-to-room!)]
                   (join! daemon (resolve-safe cfg) room)
                    ;; Address future posts in this room to the invited agent so
                    ;; it actually replies (participants listen on [:to id]).
                   (when select-agent! (select-agent! aid))
                   (say (str "Invited " (name aid)
                             ". Your messages here now go to it."))))))))})

;; Per-room interaction mode (t3code's /plan as thread state, no LLM turn). The
;; daemon's system-prompt assembly reads this to prepend a planning instruction.
(defonce room-modes (atom {}))   ; room-id → :plan | :build

(defn room-mode [room-id] (get @room-modes room-id :build))

(register!
 {:name "plan"
  :description "Switch this room to planning mode (no turn is sent)"
  :argument-hint nil
  :run (fn [{:keys [room notify!]}]
         (when room (swap! room-modes assoc (:id room) :plan))
         (when notify! (notify! "Planning mode: I'll think through the approach and wait before implementing."))
         {:handled? true :no-turn? true})})

(register!
 {:name "build"
  :description "Switch this room back to normal build mode (no turn is sent)"
  :argument-hint nil
  :run (fn [{:keys [room notify!]}]
         (when room (swap! room-modes assoc (:id room) :build))
         (when notify! (notify! "Build mode."))
         {:handled? true :no-turn? true})})

;; ----------------------------------------------------------------------------
;; /sandbox <agent> — read-only inspection of an agent's SCI sandbox (the
;; per-[room,agent] session): the vars it has defined this session + the
;; namespaces it can call. Agent-scoped because each agent owns its own sandbox
;; (see the per-agent context model). No mutation — pairs with the executing
;; tool-commands (e.g. /clojure_eval <agent> …) added later.
;; ----------------------------------------------------------------------------

(defn- format-sandbox-report
  [aid {:keys [vars namespaces ns-count]}]
  (let [vline (if (seq vars)
                (str (count vars) " var" (when (not= 1 (count vars)) "s") ": "
                     (str/join ", " vars))
                "no vars defined yet")
        nshow (take 24 namespaces)
        nline (str ns-count " namespace" (when (not= 1 ns-count) "s")
                   (when (seq nshow)
                     (str ": " (str/join ", " nshow)
                          (when (> (count namespaces) (count nshow)) ", …"))))]
    (str "🧰 " (name aid) " sandbox\n· " vline "\n· " nline)))

(register!
 {:name "sandbox"
  :description "Inspect an agent's SCI sandbox — its defined vars + namespaces"
  :argument-hint "<agent>"
  :run (fn [{:keys [room args-str agent-id notify!]}]
         (require 'dvergr.agent.room-context 'dvergr.sandbox)
         (let [lookup (resolve 'dvergr.agent.room-context/lookup)
               status (resolve 'dvergr.sandbox/sandbox-status)
               say    (fn [m] (when notify! (notify! m))
                        {:handled? true :no-turn? true :reply m})
               aid    (or (some-> args-str str/trim (str/split #"\s+") first
                                  not-empty (str/replace #"^@" "") keyword)
                          agent-id)]
           (cond
             (nil? room) (say "No room here.")
             (nil? aid)  (say "Usage: /sandbox <agent> — e.g. /sandbox var")
             :else
             (if-let [cc (lookup (:id room) aid)]
               (say (format-sandbox-report aid (status (:sci-ctx cc))))
               (say (str "@" (name aid) " has no active sandbox yet "
                         "— message it once so its session spins up."))))))})
