#!/usr/bin/env bash
# Check out SpreadsheetBench at the pinned commit and unpack verified_400 where Dvergr's
# SpreadsheetBench provider reads it. Nothing of upstream is copied into this repository:
# driver.py and grade.py read upstream's prompts, code-execution helpers and comparison
# from this checkout.
#
#   SPREADSHEETBENCH_REPO  checkout (default ~/.cache/dvergr-bench/spreadsheetbench/repo)
#   SPREADSHEETBENCH_ROOT  verified_400 (default ~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400)
set -euo pipefail
COMMIT=49b73a94775fb489063f60ca1865e3a650079a79
REPO=${SPREADSHEETBENCH_REPO:-$HOME/.cache/dvergr-bench/spreadsheetbench/repo}
DATA=${SPREADSHEETBENCH_ROOT:-$HOME/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400}

if [ ! -d "$REPO/.git" ]; then
  git clone https://github.com/RUCKBReasoning/SpreadsheetBench "$REPO"
fi
git -C "$REPO" fetch --quiet origin "$COMMIT" 2>/dev/null || true
git -C "$REPO" checkout --quiet "$COMMIT"
echo "SpreadsheetBench at $(git -C "$REPO" rev-parse HEAD)"

if [ ! -f "$DATA/dataset.json" ]; then
  mkdir -p "$(dirname "$DATA")"
  tar xzf "$REPO/data/spreadsheetbench_verified_400.tar.gz" -C "$(dirname "$DATA")"
  [ "$(basename "$DATA")" = spreadsheetbench_verified_400 ] ||
    mv "$(dirname "$DATA")/spreadsheetbench_verified_400" "$DATA"
fi
echo "verified_400 at $DATA ($(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))))" "$DATA/dataset.json") tasks)"
