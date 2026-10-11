"""Build BIRD's reference prompt for each question in ids.txt.

The prompt is upstream's generate_combined_prompts_one(db_path, question, knowledge)
(DAMO-ConvAI bird/llm/src/gpt_request.py, vendored unmodified in upstream/), called with
the question's evidence as knowledge, as run_gpt.sh's use_knowledge=True run does. The
functions are taken from the vendored file with `ast`, so their code runs as written
without importing the module (its imports need openai<1.0, backoff, sqlparse, ...).

--columns adds one line after the question:
    -- Return exactly the columns the question asks for, no extra columns.

usage: python3 prompts.py [--columns] [--bird-root DIR] [--ids FILE] OUT.json
BIRD root: --bird-root, else $BIRD_ROOT, else ~/.cache/dvergr-bench/bird/dev_20240627
(BIRD's dev.zip unpacked, with dev_databases.zip unpacked in it).
"""
import argparse, ast, json, os, sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
COLUMNS_LINE = "\n-- Return exactly the columns the question asks for, no extra columns."
COT = "\nGenerate the SQL after thinking step by step: "


def upstream_functions(path, names):
    """The named top-level functions of an upstream source file, defined in one namespace."""
    tree = ast.parse(open(path).read(), path)
    ns = {"sqlite3": sqlite3}
    mod = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names],
                     type_ignores=[])
    exec(compile(mod, path, "exec"), ns)
    missing = set(names) - set(ns)
    assert not missing, missing
    return ns


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--columns", action="store_true")
    ap.add_argument("--bird-root", default=os.environ.get("BIRD_ROOT") or
                    os.path.expanduser("~/.cache/dvergr-bench/bird/dev_20240627"))
    ap.add_argument("--ids", default=os.path.join(HERE, "ids.txt"))
    a = ap.parse_args()
    up = upstream_functions(os.path.join(HERE, "upstream", "gpt_request.py"),
                            ["nice_look_table", "generate_schema_prompt", "generate_comment_prompt",
                             "cot_wizard", "generate_combined_prompts_one"])
    dev = {q["question_id"]: q for q in json.load(open(os.path.join(a.bird_root, "dev.json")))}
    ids = [int(x) for x in open(a.ids).read().split()]
    out = []
    for qid in ids:
        q = dev[qid]
        db = os.path.join(a.bird_root, "dev_databases", q["db_id"], q["db_id"] + ".sqlite")
        prompt = up["generate_combined_prompts_one"](db_path=db, question=q["question"], knowledge=q["evidence"])
        if a.columns:
            assert prompt.count(COT) == 1
            prompt = prompt.replace(COT, COLUMNS_LINE + COT)
        out.append({"qid": qid, "db": q["db_id"], "prompt": prompt})
    json.dump(out, open(a.out, "w"))
    print(len(out), "prompts; mean chars", sum(len(p["prompt"]) for p in out) // len(out))


if __name__ == "__main__":
    main()
