# SCI Runtime Integration Design

**Date**: 2026-01-25
**Status**: 🎨 Design Exploration

## Overview

Design exploration for integrating the chat/agent turn loop with SCI contexts, enabling true sandboxed execution for `:sci` and `:shared-sci` agents.

## Current Architecture

```
ask! → run-agent-task-spin
         ↓
       create-agent-execution-context
         ↓
    ┌────┴────────┐
 :native      :sci/:shared-sci
    ↓              ↓
    │          Creates SCI ctx
    │          Forks runtime
    └──────┬───────┘
           ↓
    chat/agent turn loop (NATIVE execution for all)
           ↓
       LLM API call
           ↓
    Tool execution (NATIVE for all)
```

**Problem**: All agents execute via native chat/agent.clj regardless of isolation mode.

## Target Architecture

```
ask! → run-agent-task-spin
         ↓
       create-agent-execution-context
         ↓
    ┌────┴────────┐
 :native      :sci/:shared-sci
    ↓              ↓
    │          Creates SCI ctx
    │          Forks runtime
    │              ↓
    ├──────────────┤
    ↓              ↓
Native turn    SCI turn execution
execution      (eval in SCI)
    ↓              ↓
LLM call       LLM call
    ↓              ↓
Native tools   SCI-wrapped tools
               (permission checked)
```

## Design Questions

### Q1: Where Does SCI Boundary Sit?

**Option A: Wrap Entire Turn Loop**

```clojure
(if (:sci-ctx agent-exec-ctx)
  ;; Evaluate entire turn logic in SCI
  (sci/eval-string* sci-ctx
    "(chat-agent/run-agent-turn! ctx opts)")

  ;; Native execution
  (chat-agent/run-agent-turn! ctx opts))
```

**Pros**:
- Clean boundary
- Agent code can customize turn logic
- Full isolation

**Cons**:
- Need to expose chat-agent namespace to SCI
- ChatContext needs SCI-safe protocols
- More complex debugging

**Option B: SCI Only for Tool Execution**

```clojure
;; Turn loop stays native
;; When tool needs execution:
(if (:sci-ctx agent-exec-ctx)
  (sci/eval-string* sci-ctx
    (format "(execute-tool %s %s)" tool-name args))
  (execute-tool tool-name args))
```

**Pros**:
- Minimal changes to turn loop
- Clearer separation of concerns
- Easier debugging

**Cons**:
- Less flexible (agent can't customize turn logic)
- Partial isolation (turn logic still native)

**Option C: Hybrid - Native Turn Loop, SCI Tool Bodies**

```clojure
;; Turn loop native
;; LLM calls native
;; Tool registration native

;; But tool IMPLEMENTATIONS run in SCI
(defn execute-tool-in-context [tool-name args agent-exec-ctx]
  (if-let [sci-ctx (:sci-ctx agent-exec-ctx)]
    ;; Tool body runs in SCI
    (let [tool-fn (get-sci-tool sci-ctx tool-name)]
      (tool-fn args))  ; Function from SCI, executes in sandbox

    ;; Native tool
    (let [tool-fn (get-native-tool tool-name)]
      (tool-fn args))))
```

**Pros**:
- Best of both worlds
- Turn loop proven/reliable (native)
- Tools sandboxed (SCI)
- Clear security boundary

**Cons**:
- Tools need dual implementation?

**Recommendation**: **Option C** - Keep turn loop native, sandbox tool bodies.

Reasoning:
- Turn loop is complex, well-tested, uses ChatContext protocols
- Security concern is primarily TOOLS (filesystem, network, code execution)
- Agent customization can happen via tool composition, not turn modification

### Q2: How to Handle Tool Execution?

**Pattern 1: Tools as Regular Functions (Current)**

```clojure
;; Native
(defn read-file [path]
  (slurp path))

;; In SCI - expose function
{'read-file read-file}

;; Problem: No permission checking, direct native access
```

**Pattern 2: Tools as Wrapped Functions**

```clojure
;; Native implementation
(defn read-file-impl [path]
  (slurp path))

;; Wrapped for SCI with permission check
(defn wrap-tool-for-sci [impl agent tool-name]
  (fn [& args]
    (when-not (agent/can-use-tool? agent tool-name)
      (throw (ex-info "Permission denied" ...)))
    (apply impl args)))

;; In SCI
{'read-file (wrap-tool-for-sci read-file-impl agent :read-file)}
```

**Pattern 3: Tools as Spins (Recommended)**

```clojure
;; Tool implementation returns spin
(defn read-file-spin [path]
  (spin
    (slurp path)))

;; Wrap with permission check
(defn wrap-tool-spin [tool-spin agent tool-name]
  (fn [& args]
    (spin
      (when-not (agent/can-use-tool? agent tool-name)
        (throw (ex-info "Permission denied" ...)))
      ;; Await the actual tool
      (await (apply tool-spin args)))))

;; In SCI - natural await syntax!
(sci/eval-string* sci-ctx
  "(let [content (await (read-file \"/path\"))]
     (process content))")
```

**Why spins?**:
- Composable with agent code (uses await)
- Uniform interface
- Enables parallel tool execution
- Better error handling
- Matches spindel programming model

**Recommendation**: **Pattern 3** - Tools as spins.

### Q3: How to Handle ChatContext in SCI?

ChatContext uses protocols and atoms. Options:

**Option A: Expose ChatContext to SCI via Protocol Methods**

```clojure
;; In SCI namespace map
{'chat-context
 {'add-message! (fn [ctx msg] (chat-ctx/add-message! ctx msg))
  'get-messages (fn [ctx] (chat-ctx/get-messages ctx))
  'get-status (fn [ctx] (chat-ctx/get-status ctx))
  ...}}
```

**Pros**: Direct access, familiar API

**Cons**: Exposes internal state management, not truly isolated

**Option B: ChatContext Lives in Native, SCI Gets View**

```clojure
;; ChatContext stays native
;; SCI gets read-only view
{'context
 {'messages (fn [] (chat-ctx/get-messages native-ctx))
  'status (fn [] (chat-ctx/get-status native-ctx))}}

;; Modifications go through controlled interface
{'context
 {'send! (fn [msg]
           ;; Validated, then calls native
           (chat-ctx/add-message! native-ctx msg))}}
```

**Pros**: Better isolation, controlled mutations

**Cons**: More ceremony

**Option C: Don't Expose ChatContext to SCI**

Turn loop manages ChatContext entirely, SCI tools don't need it.

**Pros**: Maximum isolation

**Cons**: Limits agent customization

**Recommendation**: **Option B** for `:sci`, **Option A** for `:shared-sci`.

Reasoning:
- `:sci` agents (untrusted) get controlled view
- `:shared-sci` agents (collaborative) get full access
- Different trust models

### Q4: How to Handle LLM API Calls?

**Option A: LLM Calls Always Native**

```clojure
;; Even for SCI agents, LLM calls happen in native context
(defn run-turn [agent-exec-ctx]
  ;; ChatContext native
  ;; LLM call native (provider.clj)
  ;; Response added to native ChatContext

  ;; If tool calls needed and :sci mode:
  (execute-tools-in-sci ...))
```

**Pros**: Simple, LLM code already works

**Cons**: Less isolation

**Option B: LLM Calls via SCI-Exposed Function**

```clojure
;; In SCI
{'llm
 {'call (fn [messages opts]
          ;; Validates, then calls native provider
          (provider/call-llm messages opts))}}
```

**Pros**: More control, can log/limit

**Cons**: More complex, duplicates provider code

**Recommendation**: **Option A** - LLM calls native.

Reasoning:
- LLM calls are already sandboxed (external API)
- Security concern is local system access (tools)
- Keep complexity low

## Proposed Design

### Architecture

```
Agent Task Request
      ↓
create-agent-execution-context
      ↓
  ┌───┴──────┐
:native   :sci/:shared-sci
  ↓          ↓
  │     Fork runtime
  │     Create SCI ctx
  │     Load tools as spins
  │          ↓
  └──────┬───┘
         ↓
    Turn Loop (NATIVE)
         ↓
    LLM Call (NATIVE)
         ↓
    Tool Calls Detected
         ↓
    ┌────┴─────┐
 Native    SCI
    ↓          ↓
Native     Execute in SCI:
tools      (await (tool-spin args))
           ↓
       Permission check
           ↓
       Native impl (if allowed)
```

### Key Components

**1. Tool Registry**

```clojure
(defn register-tool-spin!
  [tool-name tool-spin-fn]
  (swap! tool-registry assoc tool-name tool-spin-fn))

;; Example tool
(register-tool-spin! :read-file
  (fn [path]
    (spin
      (slurp path))))
```

**2. SCI Tool Exposure**

```clojure
(defn create-sci-tool-namespace
  [agent tool-registry]
  (into {}
    (for [[tool-name tool-spin-fn] @tool-registry
          :when (agent/can-use-tool? agent tool-name)]
      [tool-name
       (fn [& args]
         (spin
           (await (apply tool-spin-fn args))))])))
```

**3. Tool Execution Router**

```clojure
(defn execute-tool-call
  [tool-name args agent-exec-ctx]
  (if-let [sci-ctx (:sci-ctx agent-exec-ctx)]
    ;; SCI execution
    (binding [rtc/*execution-context* (:runtime agent-exec-ctx)]
      (sci/eval-string* sci-ctx
        (format "(await (%s %s))"
                tool-name
                (pr-str args))))

    ;; Native execution
    (let [tool-fn (get-tool tool-name)]
      @(tool-fn args))))
```

**4. Turn Loop Integration**

```clojure
(defn run-agent-turn!
  [ctx agent-exec-ctx opts]
  ;; 1. LLM call (always native)
  (let [response (provider/call-llm ...)]

    ;; 2. Add response to context
    (chat-ctx/add-message! ctx response)

    ;; 3. If tool calls:
    (if-let [tool-calls (:tool-calls response)]
      (do
        ;; Execute tools in appropriate context
        (doseq [tc tool-calls]
          (let [result (execute-tool-call
                         (:name tc)
                         (:args tc)
                         agent-exec-ctx)]
            ;; Add tool result to context
            (chat-ctx/add-message! ctx
              {:role :tool
               :content result})))
        :continue)

      ;; No tool calls, check if complete
      (if (:stop-reason response)
        :complete
        :continue))))
```

## Integration Points

### 1. In `dvergr.agent.sci`

Add:
```clojure
(defn create-sci-tool-namespace [agent tools] ...)
(defn execute-in-sci [sci-ctx code] ...)
```

### 2. In `dvergr.tools`

Convert to spins:
```clojure
(defn read-file-spin [path]
  (spin (slurp path)))

(defn write-file-spin [path content]
  (spin (spit path content)))
```

### 3. In `dvergr.chat.agent`

Add routing:
```clojure
(defn execute-tool-call [tool-name args agent-exec-ctx]
  ...)
```

## Migration Strategy

### Phase 1: Tool Spins (No SCI Yet)

Convert tools to spins, test with native agents:

```clojure
;; Before
(def result (read-file "/path"))

;; After
(def result (await (read-file-spin "/path")))
```

Benefits:
- Parallel tool execution
- Better composition
- Prepares for SCI

### Phase 2: SCI Tool Execution

Add SCI routing for tool execution:

```clojure
;; Native agents: direct tool spins
;; SCI agents: tools via SCI eval
```

### Phase 3: Permission Enforcement

Add middleware:

```clojure
(def execute-tool
  (-> execute-tool-call
      (with-permission-check)
      (with-logging)
      (with-rate-limiting)))
```

## Testing Strategy

### Unit Tests

```clojure
(deftest test-tool-permission-check
  (let [agent (make-agent {:tools #{:read-file}})]
    (is (can-use-tool? agent :read-file))
    (is (not (can-use-tool? agent :write-file)))))

(deftest test-sci-tool-execution
  (let [sci-ctx (create-agent-sci-context ...)
        result (sci/eval-string* sci-ctx
                 "(await (read-file \"/test\"))")]
    (is (= expected result))))
```

### Integration Tests

```clojure
(deftest test-native-agent-tools
  (binding [rtc/*execution-context* rt]
    (let [agent (make-agent {:isolation :native})
          result (ask! agent "Read and summarize file")]
      (is (successful? result)))))

(deftest test-sci-agent-permission-denied
  (binding [rtc/*execution-context* rt]
    (let [agent (make-agent {:isolation :sci :tools #{}})
          result (ask! agent "Write to file")]
      (is (= :error (:status result)))
      (is (re-find #"Permission denied" (:error result))))))
```

## Open Questions

1. **Tool Result Format**: Return spins or values?
   - Spins: More composable
   - Values: Simpler for LLM

2. **Error Handling**: How to surface SCI errors to LLM?
   ```clojure
   (try
     (sci/eval-string* ...)
     (catch Exception e
       {:error (.getMessage e)
        :type :sci-error}))
   ```

3. **Timeouts**: Should SCI tool execution have timeout?
   ```clojure
   (timeout (execute-in-sci ...) 30000 :timeout)
   ```

4. **State Sharing**: Can `:shared-sci` agents share defs between turns?
   ```clojure
   ;; Agent 1, turn 1
   (sci/eval-string* shared-ctx "(def helper (fn [x] ...))")

   ;; Agent 2, turn 1
   (sci/eval-string* shared-ctx "(helper 42)")  ; Works?
   ```

   Answer: Yes, that's the point of `:shared-sci`!

## Success Criteria

Integration complete when:

- ✅ Tools execute in appropriate context (native vs SCI)
- ✅ Permission checks enforced at runtime
- ✅ SCI agents cannot access unauthorized tools
- ✅ Native agents execute without overhead
- ✅ Error messages clear and actionable
- ✅ All existing tests pass
- ✅ New isolation tests pass

## Next Steps

1. **Design Review**: Validate this design
2. **Prototype Tool Spins**: Convert 2-3 tools to spins
3. **Test with Native Agents**: Ensure no regression
4. **Add SCI Routing**: Implement execute-tool-call router
5. **Integration Test**: Full workflow with `:sci` agent
6. **Documentation**: Update examples and guides

## References

- `docs/AGENT_SCI_INTEGRATION.md` - Current status
- `src/dvergr/agent/sci.clj` - SCI infrastructure
- `../spindel/docs/SCI_COMPLETE_INTEGRATION.md` - Spindel SCI work
- `docs/ITREE_ANALYSIS.md` - Effect middleware patterns
