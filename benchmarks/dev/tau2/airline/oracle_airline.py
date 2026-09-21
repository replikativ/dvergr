"""Reference oracle for Dvergr's Clojure transcription of tau2-bench `airline`.

NOT part of Dvergr's runtime. It drives the REAL upstream airline environment
(`tau2.domains.airline.environment.get_environment`, `Environment.get_response`)
so the Clojure port can be checked for byte-identical tool outputs, error
flags, and database hashes on the same call sequences.

Run from the pinned tau2-bench checkout:

    uv run --no-sync python /path/to/dvergr/benchmarks/dev/tau2/airline/oracle_airline.py \
        replay corpus.json out.json [--per-call-hash]
    uv run --no-sync python .../oracle_airline.py schema out.json
    uv run --no-sync python .../oracle_airline.py prompts out.json

`corpus.json` is a list of {"id": str, "calls": [{"name": str,
"arguments": {...}, "requestor": "assistant"|"user"}]}. Every sequence
starts from a freshly loaded database (airline tasks have no initial state).
With --per-call-hash the DB hash after every call is recorded as well
(slow: ~0.1 s per hash; meant for localizing a divergence).
"""

import json
import os
import sys

from loguru import logger

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import oracle as shared  # noqa: E402  (benchmarks/dev/tau2/oracle.py)

from tau2.data_model.message import ToolCall  # noqa: E402

DOMAIN = "airline"


def replay(sequences, per_call_hash=False):
    from tau2.domains.airline.environment import get_environment

    results = []
    for seq in sequences:
        env = get_environment()
        outputs, hashes = [], []
        for i, call in enumerate(seq["calls"]):
            msg = env.get_response(
                ToolCall(
                    id=f"{seq['id']}-{i}",
                    name=call["name"],
                    arguments=call["arguments"],
                    requestor=call.get("requestor", "assistant"),
                )
            )
            outputs.append({"content": msg.content, "error": msg.error})
            if per_call_hash:
                hashes.append(env.get_db_hash())
        out = {"id": seq["id"], "outputs": outputs, "db_hash": env.get_db_hash()}
        if per_call_hash:
            out["db_hashes"] = hashes
        results.append(out)
    return results


def schema():
    import pydantic

    out = shared.schema(DOMAIN)
    env = shared.fresh_env(DOMAIN)
    out["mutating"] = {name: env.tools.tool_mutates_state(name) for name in env.tools.tools}
    out["tool_types"] = {name: env.tools.tool_type(name).value for name in env.tools.tools}
    out["pydantic"] = pydantic.VERSION
    return out


def main():
    # Tool bodies log at DEBUG/INFO/WARNING; silence them (no semantic effect).
    logger.remove()
    mode = sys.argv[1]
    if mode == "replay":
        with open(sys.argv[2]) as fp:
            out = replay(json.load(fp), per_call_hash="--per-call-hash" in sys.argv)
        target = sys.argv[3]
    elif mode == "schema":
        out, target = schema(), sys.argv[2]
    elif mode == "prompts":
        out, target = shared.prompts(DOMAIN), sys.argv[2]
    else:
        raise SystemExit(f"unknown mode {mode}")
    with open(target, "w") as fp:
        json.dump(out, fp, indent=1)


if __name__ == "__main__":
    main()
