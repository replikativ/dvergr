# Dvergr Tests

## Running Tests

### All non-integration tests
```bash
clojure -M:test --skip-meta :integration
```

### Specific test
```bash
clojure -M:test --focus dvergr.code-metadata-test/test-code-metadata-extraction
```

### All tests (including integration)
```bash
clojure -M:test
```

Note: Integration tests require:
- Running nREPL server (`.nrepl-port` file present)
- `FIREWORKS_API_KEY` environment variable set

## Test Structure

### `dvergr.code-metadata-test`

Tests for code metadata indexing and querying (Phase 1D):

1. **test-code-metadata-extraction** - Tests rewrite-clj based metadata extraction
   - Validates function name, namespace, type, line numbers
   - Validates docstring and arglist extraction
   - Validates call graph extraction (functions called)

2. **test-code-metadata-storage-and-queries** - Tests datahike storage and query functions
   - `find-function` by name and qualified name
   - `list-functions` all and by namespace
   - `find-callers` - what calls a given function
   - `find-callees` - what a function calls
   - `search-functions-by-doc` - search by docstring

3. **test-auto-indexing-integration** - Tests auto-indexing hooks
   - Verifies code is automatically indexed when stored
   - Tests immediate queryability after indexing

4. **test-agent-workflow-with-code-queries** (integration test)
   - End-to-end test with actual agent
   - Agent creates Clojure file with functions
   - Agent queries indexed code metadata
   - Validates agent can use `code_query` tool

## REPL Testing

You can also run tests interactively in the REPL:

```clojure
(require '[dvergr.code-metadata-test :as cmt] :reload)
(clojure.test/run-tests 'dvergr.code-metadata-test)

;; Or specific tests
(clojure.test/run-test cmt/test-code-metadata-extraction)
```

See the `(comment ...)` block at the end of `code_metadata_test.clj` for more examples.
