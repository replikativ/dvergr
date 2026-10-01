# Bank booking (SKR04, DATEV)

A German bookkeeper's daily work: for each line of a bank statement, the contra account
(Gegenkonto, SKR04) and the DATEV tax key (BU-Schlüssel) it is booked with. This bundle
is the demo of a **case pack built from a firm's own history**:

1. a year of a synthetic Mandant's bank bookings (Musterfirma GmbH, 264 lines, about 30
   vendors and customers) is written as a DATEV EXTF Buchungsstapel with Kontor's codec,
   as DATEV exports it (`../../data/bank-booking-buchungsstapel.csv`);
2. Kontor's importer (`kontor.import-datev.buchungsstapel`) reads it back, the path a
   real export takes;
3. `dvergr.catalog.casepack` certifies the cases and writes this bundle.

Certification found what real histories have: a voucher number used twice, the same
transaction booked to two accounts, and a line left unbooked (`certification.edn`);
259 cases remain. Each case is the bank's date, amount and text (`/docs/case.edn`), with
the chart and tax keys the firm uses (`/docs/kontenrahmen.txt`); the answer is
`{"gegenkonto" "6805" "bu" "9"}`, graded per field.

Regenerate with `(dvergr.benchmarks.bankbooking/write-example! "examples/workflows/bank-booking" "examples/data/bank-booking-buchungsstapel.csv")`
(benchmarks alias). Benchmark a sample:

    clojure -M -m dvergr.catalog.room-run examples/workflows/bank-booking \
      --models codex-subscription-luna --cases 30

(Claude Code CLI models work in the attempt's world through a daemon's MCP tools: benchmark
them with `catalog_benchmark` in a daemon.) `report.md` beside the experiment is the pilot
report: the frontier, which checks fail, and the certification.
