# Company cash

Four reproducible synthetic small businesses with invoices, bank statements,
correspondence and hidden scoring parameters. The standard workflow scores the
final cash plan and forecasts. It does not execute the requested model artifact.

Regeneration, expansion, the native executable-model experiment and its limits
are documented in [company-cash](../../../benchmarks/doc/company-cash.md).

Generate to an empty output directory:

```sh
clojure -M:benchmarks -m dvergr.benchmarks.company-cash target/company-cash-v1
```

The twenty original case data/gold files are pinned by regression hashes. Public
structured observations are an additional fixture. Synthetic truth and checker
calibration are not evidence of fidelity to an actual firm.
