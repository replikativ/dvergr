"""AutomationBench as a stateless service for dvergr (doc/benchmarks.md).

Runs inside an AutomationBench checkout's environment (`uv run python
sidecar.py`) and answers one JSON request per stdin line with one JSON line on
stdout. It holds no episode state: the world is passed in and out as JSON, so
dvergr keeps it in the Run's forked world (copy-on-write, checkpointed, resumed
with everything else) and this process can serve every Attempt at once.

Everything that decides a score is upstream's code, called as upstream's runner
calls it: the task set, the tool implementations and their schemas, the
argument normalization (`{}` means "no value"), the error text of a failed call
(`f"{e}"`), and the rubric (`partial_credit`, `task_completed_correctly`).

Requests (`op`):
  hello                                  revision, task count
  tasks    {domains}                     [{domain id name contract}]
  start    {domain id toolset at seed}   prompt, tool schemas, initial world, at
  call     {domain id world name arguments at seed}
                                         {world content error at}
  grade    {domain id world}             partial credit, pass, per assertion
  replay   {domain id start calls toolset}
                                         the calls in one process on one world,
                                         as upstream runs an episode: {world digest}

Every world a response carries comes with its `digest`, the sha256 of its
canonical JSON, so both sides compare worlds without shipping them twice.
"""

import contextlib
import copy
import hashlib
import inspect
import json
import os
import random
import subprocess
import sys
import time
import traceback

import time_machine
from pathlib import Path

from automationbench.domains import get_domain_dataset
from automationbench.rubric import partial_credit, task_completed_correctly
from automationbench.runner import compute_allowed_services, strip_none_values
from automationbench.schema.world import WorldState
from automationbench.task_contract import task_contract_sha256
from automationbench.tools import ALL_TOOLS
from automationbench.tools.api import API_TOOLS
from verifiers.envs.stateful_tool_env import filter_signature
from verifiers.utils.tool_utils import convert_func_to_tool_def

DOMAINS = ["sales", "marketing", "operations", "support", "finance", "hr", "simple"]

_tasks = {}


def _load(domain):
    if domain not in _tasks:
        rows = {}
        for row in get_domain_dataset(domain):
            info = row["info"]
            info = json.loads(info) if isinstance(info, str) else info
            rows[str(row["example_id"])] = {"example_id": row["example_id"],
                                            "prompt": row["prompt"], "info": info}
        _tasks[domain] = rows
    return _tasks[domain]


def _task(domain, task_id):
    task = _load(domain).get(str(task_id))
    if task is None:
        raise KeyError(f"unknown task {domain}/{task_id}")
    return task


def _contract(task):
    return task_contract_sha256(example_id=task["example_id"], prompt=task["prompt"],
                                info=task["info"])


def _initial(task):
    """The initial world as upstream's `setup_state` builds it."""
    info = copy.deepcopy(task["info"])
    initial = strip_none_values(info.get("initial_state", {}))
    assertions = [strip_none_values(a) for a in info.get("assertions", [])]
    world = WorldState(**initial)
    world.meta.allowed_services = compute_allowed_services(
        initial, assertions, info.get("zapier_tools", []))
    return world, initial, assertions


def _world(data):
    return WorldState(**data)


def _dump(world):
    return world.model_dump(mode="json")


def _digest(data):
    canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _toolset(name):
    if name == "api":
        return API_TOOLS
    if name == "limited_zapier":
        return ALL_TOOLS
    raise ValueError(f"unsupported toolset {name}")


def _tool_schema(tool):
    """The schema upstream's runner shows (StatefulToolEnv.add_tool): an
    injected `world` argument is hidden, with its $defs entry."""
    skip = ["world"] if "world" in inspect.signature(tool).parameters else []
    d = convert_func_to_tool_def(filter_signature(tool, skip))
    params = d.parameters
    for arg in skip:
        props = params.get("properties")
        if isinstance(props, dict) and arg in props:
            ref = props.pop(arg).get("$ref")
            if ref and ref.split("/")[-1] in params.get("$defs", {}):
                params["$defs"].pop(ref.split("/")[-1])
        if isinstance(params.get("required"), list) and arg in params["required"]:
            params["required"].remove(arg)
    if "$defs" in params and not params["$defs"]:
        params.pop("$defs")
    return {"type": "function",
            "function": {"name": d.name, "description": d.description, "parameters": params}}


def _schemas(toolset, task):
    tools = _toolset(toolset)
    if toolset == "limited_zapier":
        allowed = set(task["info"].get("zapier_tools", []))
        tools = [t for t in tools if t.__name__ in allowed]
    return [_tool_schema(t) for t in tools]


@contextlib.contextmanager
def _pinned(at_ms, seed):
    """Run at instant `at_ms` (epoch ms, the clock frozen) with randomness
    drawn from `seed`. Upstream's worlds take wall-clock defaults
    (`default_factory=datetime.now`) and random ids (`uuid4`, whose bytes come
    from `os.urandom`); pinned, a replay makes the same world."""
    rng = random.Random(seed)
    state = random.getstate()
    real_urandom = os.urandom
    os.urandom = lambda n: rng.getrandbits(8 * n).to_bytes(n, "big") if n else b""
    random.seed(seed)
    try:
        with time_machine.travel(at_ms / 1000.0, tick=False):
            yield
    finally:
        os.urandom = real_urandom
        random.setstate(state)


def _now_ms():
    return int(time.time() * 1000)


def _apply(world, name, arguments, at, seed, toolset):
    """One tool call on `world` (mutated in place) as upstream's
    StatefulToolEnv makes it: `(content, error?)`. Under limited_zapier
    upstream offers the task's tools but executes any."""
    tools = {t.__name__: t for t in _toolset(toolset)}
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except Exception as e:  # upstream: the parse error is the tool message
            return f"{e}", True
    if not isinstance(arguments, dict):
        e = ValueError(f"Expected tool arguments to be a dict, got "
                       f"{type(arguments).__name__}: {arguments}")
        return f"{e}", True
    if name not in tools:
        return f"'{name}'", True
    args = {k: v for k, v in arguments.items() if not (isinstance(v, dict) and len(v) == 0)}
    if "world" in inspect.signature(tools[name]).parameters:
        args["world"] = world
    with _pinned(at, seed):
        try:
            result = tools[name](**args)
            return str(result), False
        except Exception as e:
            return f"{e}", True


def _call(domain, task_id, world_data, name, arguments, at, seed, toolset="api"):
    """One call, the world passed in and out. `at` nil: now (returned)."""
    _task(domain, task_id)
    at = _now_ms() if at is None else at
    world = _world(world_data)
    content, error = _apply(world, name, arguments, at, seed, toolset)
    data = _dump(world)
    return {"world": data, "digest": _digest(data), "content": content, "error": error,
            "at": at}


def _replay(domain, task_id, start, calls, toolset="api"):
    """The episode in one process on one world object, never serialized in
    between, as upstream's runner makes it: the initial world at `start`
    (`{at seed}`), then `calls` at their recorded instants and seeds."""
    with _pinned(start["at"], start.get("seed", 0)):
        world, _, _ = _initial(_task(domain, task_id))
    contents = [_apply(world, c["name"], c.get("arguments") or {}, c["at"], c.get("seed", 0),
                       toolset)[0]
                for c in calls]
    data = _dump(world)
    return {"world": data, "digest": _digest(data), "contents": contents}


def _grade(domain, task_id, world_data):
    task = _task(domain, task_id)
    _, initial, assertions = _initial(task)
    state = {"info": {**task["info"], "assertions": assertions},
             "initial_state": copy.deepcopy(initial),
             "world": _world(world_data)}
    score = partial_credit(state)
    return {"partial_credit": score,
            "passed": task_completed_correctly(state) == 1.0,
            "assertions": state.get("_assertion_results", [])}


def _revision():
    try:
        import automationbench
        pkg = Path(automationbench.__file__).resolve().parent.parent
        return subprocess.run(["git", "-C", str(pkg), "rev-parse", "HEAD"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return None


def handle(req):
    op = req.get("op")
    if op == "hello":
        return {"revision": _revision(), "python": sys.version.split()[0]}
    if op == "tasks":
        out = []
        for d in req.get("domains") or DOMAINS:
            for tid, task in _load(d).items():
                out.append({"domain": d, "id": tid,
                            "name": task["info"].get("task_name"),
                            "contract": _contract(task)})
        return out
    if op == "start":
        task = _task(req["domain"], req["id"])
        at = req.get("at") or _now_ms()
        with _pinned(at, req.get("seed", 0)):
            world, _, _ = _initial(task)
        prompt = [{"role": m["role"], "content": m["content"]} for m in task["prompt"]]
        data = _dump(world)
        return {"prompt": prompt, "tools": _schemas(req.get("toolset", "api"), task),
                "world": data, "digest": _digest(data), "contract": _contract(task), "at": at}
    if op == "call":
        return _call(req["domain"], req["id"], req["world"], req["name"],
                     req.get("arguments") or {}, req.get("at"), req.get("seed", 0),
                     req.get("toolset", "api"))
    if op == "replay":
        return _replay(req["domain"], req["id"], req["start"], req.get("calls") or [],
                       req.get("toolset", "api"))
    if op == "grade":
        return _grade(req["domain"], req["id"], req["world"])
    raise ValueError(f"unknown op {op}")


def main():
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            req = json.loads(line)
            out = {"ok": True, "result": handle(req)}
        except Exception as e:
            out = {"ok": False, "error": f"{type(e).__name__}: {e}",
                   "trace": traceback.format_exc(limit=5)}
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
