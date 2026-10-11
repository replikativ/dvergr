"""Exact McNemar test between two candidates graded on the same cases.

Each argument is a verdicts file, a JSON object {case-id: verdict}. A verdict is 0/1, a
boolean, or an object; for objects name the field after a colon (FILE.json:lo reads
verdict["lo"], as in spreadsheetbench/grade.py's output). Only the cases two candidates
disagree on count: b = A right and B wrong, c = the reverse, and
p = min(1, 2 * sum_{i <= min(b, c)} C(b + c, i) / 2^(b + c)), the two-sided exact binomial
test of b against Binomial(b + c, 1/2). Both files must cover the same case ids.

usage: python3 mcnemar.py A.json[:field] B.json[:field] [--ids FILE]
"""
import argparse, json
from math import comb


def load(arg):
    path, _, field = arg.partition(":")
    v = json.load(open(path))
    return {str(k): bool(x[field] if field else x) for k, x in v.items()}


def p_value(b, c):
    n = b + c
    if n == 0:
        return 1.0
    return min(1.0, 2 * sum(comb(n, i) for i in range(min(b, c) + 1)) / 2 ** n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--ids", help="restrict to these case ids (whitespace-separated)")
    x = ap.parse_args()
    a, b = load(x.a), load(x.b)
    ids = open(x.ids).read().split() if x.ids else sorted(a)
    missing = [i for i in ids if i not in a or i not in b]
    if missing:
        raise SystemExit(f"not graded in both files: {missing[:10]} ({len(missing)})")
    only_a = [i for i in ids if a[i] and not b[i]]
    only_b = [i for i in ids if b[i] and not a[i]]
    print(f"n {len(ids)}  A {sum(a[i] for i in ids)}  B {sum(b[i] for i in ids)}  "
          f"only A {len(only_a)}  only B {len(only_b)}  p = {p_value(len(only_a), len(only_b)):.3g}")
    print("  only A:", " ".join(only_a))
    print("  only B:", " ".join(only_b))


if __name__ == "__main__":
    main()
