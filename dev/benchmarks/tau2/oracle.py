"""Reference oracle for Dvergr's Clojure transcription of tau2-bench domains.

This is NOT part of Dvergr's runtime. It runs the upstream Python environment
once per port version so the Clojure port can be checked for equivalence:
identical tool outputs, error flags, and final database hashes for the same
call sequences.

Run from a tau2-bench checkout:

    uv run python /path/to/dvergr/dev/benchmarks/tau2/oracle.py \
        retail replay input.json output.json
    uv run python .../oracle.py retail schema output.json

`input.json` is a list of {"id": str, "calls": [{"name": str,
"arguments": {...}}]}. Every sequence starts from a fresh database.
"""

import json
import sys

from tau2.data_model.message import ToolCall
import importlib


def fresh_env(domain):
    # Import the domain directly: tau2.registry also imports optional voice
    # providers that the text environments do not need.
    module = importlib.import_module(f"tau2.domains.{domain}.environment")
    return module.get_environment()


def entity_snapshot(db):
    """Top-level collections as plain JSON data, for localized diffs."""
    return db.model_dump()


def changed_entities(before, after):
    changed = {}
    for coll, entries in after.items():
        if not isinstance(entries, dict):
            continue
        for key, value in entries.items():
            if before.get(coll, {}).get(key) != value:
                changed.setdefault(coll, {})[key] = value
    return changed


def replay(domain, sequences):
    results = []
    for seq in sequences:
        env = fresh_env(domain)
        before = entity_snapshot(env.tools.db)
        outputs = []
        for i, call in enumerate(seq["calls"]):
            msg = env.get_response(
                ToolCall(
                    id=f"{seq['id']}-{i}",
                    name=call["name"],
                    arguments=call["arguments"],
                    requestor="assistant",
                )
            )
            outputs.append({"content": msg.content, "error": msg.error})
        after = entity_snapshot(env.tools.db)
        results.append(
            {
                "id": seq["id"],
                "outputs": outputs,
                "db_hash": env.get_db_hash(),
                "changed": changed_entities(before, after),
            }
        )
    return results


def schema(domain):
    env = fresh_env(domain)
    return {
        "policy": env.policy,
        "initial_db_hash": env.get_db_hash(),
        "tools": [t.openai_schema for t in env.get_tools()],
    }


def prompts(domain):
    """Exact agent/user-simulator system prompts for every task."""
    from tau2.agent.llm_agent import LLMAgent
    from tau2.orchestrator.orchestrator import DEFAULT_FIRST_AGENT_MESSAGE
    from tau2.user.user_simulator import UserSimulator

    module = importlib.import_module(f"tau2.domains.{domain}.environment")
    env = module.get_environment()
    agent = LLMAgent(tools=env.get_tools(), domain_policy=env.policy, llm="none")
    users = {}
    for task in module.get_tasks(None):
        user = UserSimulator(llm="none", instructions=task.user_scenario)
        users[task.id] = user.system_prompt
    return {
        "agent_system": agent.system_prompt,
        "first_agent_message": DEFAULT_FIRST_AGENT_MESSAGE.content,
        "user_system": users,
    }


def judge_prompts(cases):
    """Capture the exact NL-assertion judge request through upstream code by
    replacing its `generate` call. `cases`: [{"id", "messages", "assertions"}]
    with tau2-shaped messages (role, content, tool_calls, id)."""
    import tau2.evaluator.evaluator_nl_assertions as nl
    from tau2.data_model.message import (
        AssistantMessage,
        ToolMessage,
        UserMessage,
    )

    captured = {}

    class Fake:
        content = '{"results": []}'

    def fake_generate(model, messages, **kwargs):
        captured["system"] = messages[0].content
        captured["user"] = messages[1].content
        return Fake()

    nl.generate = fake_generate

    def to_message(m):
        if m["role"] == "assistant":
            calls = [
                ToolCall(id=c["id"], name=c["name"], arguments=c["arguments"])
                for c in (m.get("tool_calls") or [])
            ] or None
            return AssistantMessage(role="assistant", content=m.get("content"), tool_calls=calls)
        if m["role"] == "user":
            return UserMessage(role="user", content=m.get("content"))
        return ToolMessage(id=m["id"], role="tool", content=m.get("content"), requestor="assistant")

    out = {}
    for case in cases:
        nl.NLAssertionsEvaluator.evaluate_nl_assertions(
            [to_message(m) for m in case["messages"]], case["assertions"]
        )
        out[case["id"]] = dict(captured)
    return out


def main():
    domain, mode = sys.argv[1], sys.argv[2]
    if mode == "replay":
        with open(sys.argv[3]) as fp:
            sequences = json.load(fp)
        out = replay(domain, sequences)
        target = sys.argv[4]
    elif mode == "judge-prompts":
        with open(sys.argv[3]) as fp:
            out = judge_prompts(json.load(fp))
        target = sys.argv[4]
    elif mode == "prompts":
        out = prompts(domain)
        target = sys.argv[3]
    elif mode == "schema":
        out = schema(domain)
        target = sys.argv[3]
    else:
        raise SystemExit(f"unknown mode {mode}")
    with open(target, "w") as fp:
        json.dump(out, fp, indent=1)


if __name__ == "__main__":
    main()
