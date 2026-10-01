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

The generator plants the defects real histories have (a voucher number used twice, the
same transaction booked to two accounts, a line left unbooked), and certification finds
and excludes them (`certification.edn`); 259 cases remain. The gold labels are one
synthetic bookkeeper's conventions, some of which a tax adviser could book differently
(card-terminal payouts straight to revenue 4400, office rent without a tax key, foreign
ad invoices without a reverse-charge key): a pilot on a firm's own export grades against
that firm's conventions. Each case is the bank's date, amount and text (`/docs/case.edn`), with
the chart and tax keys the firm uses (`/docs/kontenrahmen.txt`); the answer is
`{"gegenkonto" "6805" "bu" "9"}`, graded per field.

Regenerate with `(dvergr.benchmarks.bankbooking/write-example! "examples/workflows/bank-booking" "examples/data/bank-booking-buchungsstapel.csv")`
(benchmarks alias). Benchmark a sample:

    clojure -M -m dvergr.catalog.room-run examples/workflows/bank-booking \
      --models codex-subscription-luna,claude-code-haiku --cases 30 --out runs

Claude Code CLI models call the attempt's tools over MCP; `room-run` serves them from a
headless daemon of its own. The state lives in `runs/bank-booking`: running the same
command again resumes it. `report.md` there is the pilot report: the frontier, which
checks fail, tokens, time and list-price cost, and the certification.
