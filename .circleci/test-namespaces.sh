#!/usr/bin/env bash
# Every test namespace of the kaocha suites (tests.edn), one per line, derived
# from the file names. dvergr.test-split checks the list against the
# namespaces kaocha itself finds before any test runs, so a namespace this
# derivation would miss fails the build instead of being skipped.
set -euo pipefail
cd "$(dirname "$0")/.."
for root in test benchmarks/test; do
  find "$root" -type f \( -name '*_test.clj' -o -name '*_test.cljc' \) |
    sed -e "s|^$root/||" -e 's|\.cljc\?$||' -e 's|/|.|g' -e 's|_|-|g'
done | sort
