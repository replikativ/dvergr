"""Summarize a graded driver.py run: scores, tokens and time per task, turns, replies without code.

tokens per task = input + output tokens summed over the task's turns (input includes cached
input), as the model layer reports them; time = the task's wall time (model calls and code
execution). A reply "without code" has no ```python block (upstream's extract_code then
passes the whole reply to the kernel).

usage: python3 summarize.py TAG [IDS_FILE]   (reads $SBREF_OUT/TAG, default ~/.cache/dvergr-bench/sbref/TAG)
"""
import json, os, statistics as st, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.expanduser(os.environ.get('SBREF_OUT') or '~/.cache/dvergr-bench/sbref')


def q(xs):
    xs = sorted(xs)
    return f'median {st.median(xs):.0f}, p90 {xs[int(0.9 * (len(xs) - 1))]:.0f}, mean {st.mean(xs):.0f}'


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    tag = sys.argv[1]
    ids = open(sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, 'ids.txt')).read().split()
    run_dir = f'{OUT}/{tag}'
    rs = {t: json.load(open(f'{run_dir}/results/{t}.json')) for t in ids}
    n = len(ids)
    if os.path.exists(f'{run_dir}/grade.json'):
        g = json.load(open(f'{run_dir}/grade.json'))
        print(f'{tag}: graded as shipped (raw) {sum(g[t]["raw"] for t in ids)}/{n}, after LibreOffice recalculation '
              f'(lo) {sum(g[t]["lo"] for t in ids)}/{n}; output files {sum(g[t]["exists"] for t in ids)}, '
              f'tasks with uncached formulas {sum(1 for t in ids if g[t]["uncached"])}')
    print('tasks with a run error:', [t for t in ids if rs[t]['error']])
    use = lambda t, k: sum((u['usage'] or {}).get(k, 0) for u in rs[t]['turns'])
    print('tokens/task', q([use(t, 'input-tokens') + use(t, 'output-tokens') for t in ids]),
          '| input mean', round(st.mean(use(t, 'input-tokens') for t in ids)),
          '| output mean', round(st.mean(use(t, 'output-tokens') for t in ids)))
    print('wall s/task', q([rs[t]['wall_s'] for t in ids]), '| turns', q([len(rs[t]['turns']) for t in ids]))
    replies = [m for t in ids for m in rs[t]['conversation'][1::2]]
    print('replies without code', sum('```python' not in (m or '') for m in replies), 'of', len(replies))


if __name__ == '__main__':
    main()
