# Contract review (a room workflow, CUAD)

Eight commercial contracts from the CUAD test split (The Atticus Project, CC BY 4.0,
https://www.atticusprojectai.org/cuad), one case each; for eight clause types, the lawyers'
annotated spans are the gold. Regenerate or enlarge with

    python3 dev/cuad_bundle.py <CUAD>/test.json examples/workflows/contract-review 8

    clojure -M -m dvergr.catalog.room-run examples/workflows/contract-review --check
