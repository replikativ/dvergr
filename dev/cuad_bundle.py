"""Build the contract-review workflow bundle from CUAD (CC BY 4.0,
https://www.atticusprojectai.org/cuad): each case a contract of the CUAD test
split with lawyers' spans for eight clause types.

    python3 dev/cuad_bundle.py ~/.cache/dvergr-bench/cuad/test.json examples/workflows/contract-review 8
"""
import json, os, sys, re

CATEGORIES = ["Governing Law", "Anti-Assignment", "Cap On Liability",
              "Termination For Convenience", "Exclusivity", "Change Of Control",
              "Non-Compete", "Audit Rights"]

def edn_str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'

def case_id(title):
    return re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')[:48]

def main(src, out, n):
    data = json.load(open(src))['data']
    picked = []
    for d in data:
        p = d['paragraphs'][0]
        ctx = p['context']
        if not (8000 <= len(ctx) <= 30000):
            continue
        gold = {}
        for q in p['qas']:
            cat = q['id'].split('__')[-1]
            if cat in CATEGORIES:
                spans = sorted({a['text'].strip() for a in q['answers'] if a['text'].strip()})
                gold[cat] = spans
        present = sum(1 for c in CATEGORIES if gold.get(c))
        if 3 <= present <= 6:          # a mix of present and absent clauses
            picked.append((d['title'], ctx, gold))
        if len(picked) >= n:
            break
    for title, ctx, gold in picked:
        cid = case_id(title)
        base = os.path.join(out, 'cases', cid)
        os.makedirs(os.path.join(base, 'fixtures', 'docs'), exist_ok=True)
        open(os.path.join(base, 'fixtures', 'docs', 'contract.txt'), 'w').write(ctx)
        entries = ' '.join(
            f'{edn_str(c)} {{:present {"true" if gold.get(c) else "false"} :spans [{" ".join(edn_str(s) for s in gold.get(c, []))}]}}'
            for c in CATEGORIES)
        open(os.path.join(base, 'gold.edn'), 'w').write(f'{{:title {edn_str(title)}\n :clauses {{{entries}}}}}\n')
    print(len(picked), 'cases')
    return picked

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 8)
