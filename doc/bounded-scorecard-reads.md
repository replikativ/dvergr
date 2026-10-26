# Bounded Scorecard reads

`experiment/scorecard-bounded` reads one exact managed Scorecard in a Room. It
uses an optional `PBoundedScorecardStore` capability; unsupported stores refuse
rather than falling back to the ordinary reader. The native Datahike store
implements this capability without changing persistence or accounting.

```clojure
(require '[dvergr.agent.experiment :as experiment]
         '[dvergr.ops :as ops])

(when-let [scorecard (experiment/scorecard-bounded
                     room content-id {:max-bytes 1048576 :max-cells 1024})]
  (ops/scorecard-summary scorecard))
```

This is a storage/domain API. Its caller must authorize access to the Room and
retain the appropriate native read lifetime. Neither a content UUID nor the
returned presentation grants authority to publish, adopt, merge or execute work.

## Read contract

The caller may lower these ceilings; unknown options, nonpositive values and
raised ceilings refuse before native lookup:

| Limit | Ceiling and default |
| --- | ---: |
| `:max-bytes` | 4 MiB |
| `:max-candidates` | 32 |
| `:max-environments` | 128 |
| `:max-repetitions` | 128 |
| `:max-cells` | 4,096 |

These limits are independent. A matrix satisfying each dimensional ceiling can
still exceed the cell or byte ceiling. This deliberately restricted reader does
not define the universe of valid native Scorecards. Overflow refuses the whole
read; it never reports a prefix as a complete result.

The implementation captures one immutable database value, resolves exact
Room/Scorecard ownership, and reads a fixed scalar header. Both outgoing Attempt
and summary relation prefixes are bounded at their cap plus one before any child
or payload expansion. Accepted members are pulled individually, including values
above Datahike's ordinary 1,000-member pull default. Attempt ownership is checked
against that same Room.

Only managed UUID payloads are accepted. The existing bounded artifact reader
checks byte limits, content identity, strict UTF-8 and nesting depth before EDN
parsing. Exactly one EDN value is required; a literal keyword cannot impersonate
the reader's EOF state. InputStream consumption stops at the byte cap plus one.
A backend that supplies an already allocated byte array is checked before
parsing, but this does not bound an allocation the backend has already performed.

Cheap shape, cardinality, repetition and matrix bounds precede canonical
validation. The same native validator and exact typed-projection comparison used
by the ordinary reader then validate the value against its stored projection. All projection facts come from
the original database snapshot, including when artifact I/O blocks while the live
connection advances. Application readers still need current authorization checks
after I/O; snapshot consistency does not establish present access authority.

## Summary presentation

`ops/scorecard-summary` is a pure fixed projection of an already validated
Scorecard. It uses the ordinary native candidate ranking, confidence intervals
and spend calculations, preserving unknown pricing and usage. It does not load
Attempt receipts, expand cell details or run pairwise comparisons. Timing and
check/judge details are omitted. Token information already present in canonical
spend remains available.

The summary can include nested spend attribution, so presentation adapters need
an independent output/display budget. A bounded reader is not a license to render
arbitrary nested data or treat edited report numbers as accounting authority.
