"""Grade the replies of ask.clj with BIRD's own evaluation.

Each reply's SQL is graded by upstream's execute_model (DAMO-ConvAI bird/llm/src/evaluation.py,
vendored unmodified in upstream/): the predicted and the gold query run on the question's
SQLite database, and the question passes when set(fetchall(pred)) == set(fetchall(gold));
an error or the 30 s meta timeout (upstream's default) scores 0.

The SQL is taken from the reply as follows (the chat adaptation; upstream's completion
models continued a prompt that ends in "SELECT " and upstream prepends 'SELECT'):
the last ```sql (or ```sqlite, or bare ```) block; else the whole reply if it starts with
SELECT or WITH; else "SELECT " + the reply.

usage: python3 grade.py [--bird-root DIR] REPLIES.json VERDICTS.json
Writes {qid: 0|1} to VERDICTS.json and prints the score, execution failures and mean tokens
per question.
"""
import argparse, collections, importlib.util, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))


def extract(content):
    t = content or ""
    blocks = re.findall(r"```(?:sqlite|sql)?\s*(.*?)```", t, re.S | re.I)
    if blocks:
        return blocks[-1].strip()
    t = t.strip()
    if re.match(r"(?i)^(select|with)\b", t):
        return t
    return "SELECT " + t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("replies")
    ap.add_argument("verdicts")
    ap.add_argument("--bird-root", default=os.environ.get("BIRD_ROOT") or
                    os.path.expanduser("~/.cache/dvergr-bench/bird/dev_20240627"))
    ap.add_argument("--meta-time-out", type=float, default=30.0)
    a = ap.parse_args()
    spec = importlib.util.spec_from_file_location("bird_upstream_evaluation",
                                                  os.path.join(HERE, "upstream", "evaluation.py"))
    ev = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ev)
    dev = {q["question_id"]: q for q in json.load(open(os.path.join(a.bird_root, "dev.json")))}
    replies = json.load(open(a.replies))
    res, usage, failed = {}, collections.Counter(), 0
    for i, r in enumerate(replies):
        q = dev[r["qid"]]
        db = os.path.join(a.bird_root, "dev_databases", q["db_id"], q["db_id"] + ".sqlite")
        if r.get("error"):
            failed += 1
        res[r["qid"]] = ev.execute_model(extract(r.get("content")), q["SQL"], db, i, a.meta_time_out)["res"]
        for k, v in (r.get("usage") or {}).items():
            usage[k] += v
    json.dump({str(k): v for k, v in res.items()}, open(a.verdicts, "w"))
    print(f"{a.replies}: {sum(res.values())} / {len(res)} correct; model calls that failed: {failed}")
    print("mean tokens per question:", {k: round(v / len(res)) for k, v in usage.items()})


if __name__ == "__main__":
    main()
