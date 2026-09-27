# Contract review (a room workflow, CUAD)

Eight commercial contracts from the CUAD test split (The Atticus Project, CC BY 4.0,
https://www.atticusprojectai.org/cuad), one case each; for eight clause types, the lawyers'
annotated spans are the gold. Regenerate or enlarge with

    python3 dev/cuad_bundle.py <CUAD>/test.json examples/workflows/contract-review 8

    clojure -M -m dvergr.catalog.room-run examples/workflows/contract-review --check

## Scoring

Per clause type: 0 for the wrong presence, 1 for a clause correctly absent, and for a clause
present 1 when the quote matches an annotated span, else 0.5. A quote matches when the
tokens overlap (F1 ≥ 0.5), when it contains the span (a whole numbered section, at most 200
tokens), or when it lies inside the span (one item of a long annotated list). CUAD annotates
sentences, and the first checker (F1 only) marked a reviewer quoting the whole section as
quoting too much: on the first run, 7 of 16 answers lost half a clause that way.

The gold is CUAD's, unchanged, including labels a reader may dispute (e.g. a six-month
notice "for any reason other than breach" that CUAD does not label Termination For
Convenience).
