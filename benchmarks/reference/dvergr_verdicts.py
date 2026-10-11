"""Verdicts of one candidate of a Dvergr experiment of the layout before homes (an artifacts/
directory), for pairing with a reference run (mcnemar.py); dvergr_verdicts.clj reads current homes.

Reads the attempt artifacts an experiment writes (DIR/artifacts/*.edn) and keeps, per case,
the latest certified attempt that has a reward and did not end in an infrastructure error.
A case passes when its reward is 1.0. The case id is the environment id's last part
(:bird.<db>/q-<question-id>, :spreadsheetbench/t-<task-id>).

usage: python3 dvergr_verdicts.py EXPERIMENT_DIR CANDIDATE OUT.json
       (CANDIDATE: the experiment's candidate id, e.g. luna or luna-sqlite)
"""
import glob, json, os, re, sys


def main():
    exp_dir, cand, out = sys.argv[1:4]
    best = {}
    for f in glob.glob(os.path.join(os.path.expanduser(exp_dir), "artifacts", "*.edn")):
        s = open(f).read()
        c = re.search(r":experiment-candidate :([A-Za-z0-9_.-]+)", s)
        env = re.search(r":environment/id :[^/\s]+/[qt]-([^,}\s]+)", s)
        if not (c and env) or c.group(1) != cand or ":termination :infrastructure-error" in s:
            continue
        r = re.search(r":attempt/reward ([0-9.]+)", s)
        if not r:
            continue
        t = re.search(r":attempt/certified-at (\d+)", s)
        rec = (int(t.group(1)) if t else 0, float(r.group(1)))
        if env.group(1) not in best or rec > best[env.group(1)]:
            best[env.group(1)] = rec
    v = {k: int(rew >= 1.0) for k, (_, rew) in sorted(best.items())}
    if not v:
        sys.exit(f"no verdicts of candidate {cand} in {exp_dir}/artifacts")
    json.dump(v, open(out, "w"))
    print(f"{exp_dir} {cand}: {sum(v.values())} / {len(v)} pass")


if __name__ == "__main__":
    main()
