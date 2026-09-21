"""Reference oracle for Dvergr's Clojure transcription of tau2-bench `telecom`.

NOT part of Dvergr's runtime. Runs the REAL upstream environment
(`tau2.domains.telecom.environment.get_environment`, manual policy, not solo)
so the port can be checked for byte-identical tool outputs, error flags, both
database hashes (agent DB and user/device DB) and env-assertion results.

Run from the pinned tau2-bench checkout:

    uv run --no-sync python /path/to/dvergr/benchmarks/dev/tau2/telecom/oracle_telecom.py \
        replay input.json output.json
    ... schema output.json
    ... prompts output.json
    ... gold-eval output.json          # upstream's own grade of the gold trajectories
    ... grade trajectories.json output.json   # upstream grade of [{"id","task","calls"}]

`input.json` for `replay` is a list of

    {"id": str,
     "task": optional task id (tasks.json, any split): initial state via set_state,
     "env_calls": optional [{"env_type", "func_name", "arguments"}] run with
                  run_env_function_call after the task's initial state
                  (initialization-only functions such as set_data_usage),
     "calls": [{"name", "arguments", "requestor": "assistant"|"user"}],
     "assertions": optional [EnvAssertion dicts] evaluated at the end}

Two upstream nondeterminisms are pinned so that replays are functions of the
input (the Clojure port does the same):

* `_apply_one_time_charge` names new draft bills `B{uuid4().hex[:8]}`; here
  uuid4 is replaced per sequence by a counter (`B00000001`, `B00000002`, ...).
* `TelecomUserTools.default_vpn_details` is a CLASS attribute that
  `break_vpn` mutates in place (server_performance -> POOR), so upstream's
  VPN behavior depends on which tasks ran earlier in the same process. It is
  reset before every sequence, i.e. every sequence behaves like a fresh
  process.
"""

import inspect
import json
import sys
import uuid
import warnings

from loguru import logger

logger.remove()
warnings.simplefilter("ignore")  # pydantic serializer warnings on junk values

from tau2.data_model.message import AssistantMessage, ToolCall, UserMessage  # noqa: E402
from tau2.data_model.tasks import EnvAssertion, EnvFunctionCall  # noqa: E402
from tau2.domains.telecom import environment as tenv  # noqa: E402
from tau2.domains.telecom import tools as ttools  # noqa: E402
from tau2.domains.telecom.user_data_model import PerformanceLevel, VpnDetails  # noqa: E402
from tau2.domains.telecom.user_tools import TelecomUserTools  # noqa: E402


class _Counter:
    n = 0


def _fake_uuid4():
    _Counter.n += 1
    return uuid.UUID(int=_Counter.n << 96)


ttools.uuid.uuid4 = _fake_uuid4


def reset_process_state():
    _Counter.n = 0
    TelecomUserTools.default_vpn_details = VpnDetails(
        server_address="192.168.1.1",
        protocol="OpenVPN",
        server_performance=PerformanceLevel.EXCELLENT,
    )


_TASKS = None


def all_tasks():
    global _TASKS
    if _TASKS is None:
        _TASKS = {t.id: t for t in tenv.get_tasks(None)}
    return _TASKS


def fresh_env(task=None):
    env = tenv.get_environment()
    if task is not None and task.initial_state is not None:
        env.set_state(
            initialization_data=task.initial_state.initialization_data,
            initialization_actions=task.initial_state.initialization_actions,
            message_history=[],
        )
    return env


def hashes(env):
    a, u = env.get_db_hash(), env.get_user_db_hash()
    return {"agent_db_hash": a, "user_db_hash": u, "db_hash": f"{a}|{u}"}


def err_text(e):
    return f"{type(e).__name__}: {e}"


def replay(sequences):
    tasks = all_tasks() if any(s.get("task") for s in sequences) else {}
    results = []
    for seq in sequences:
        reset_process_state()
        out = {"id": seq["id"]}
        try:
            env = fresh_env(tasks[seq["task"]] if seq.get("task") else None)
        except Exception as e:  # noqa: BLE001
            out["init_error"] = err_text(e)
            results.append(out)
            continue
        env_outputs = []
        for c in seq.get("env_calls", []):
            try:
                res = env.run_env_function_call(EnvFunctionCall(**c))
                env_outputs.append({"result": repr(res), "error": None})
            except Exception as e:  # noqa: BLE001
                env_outputs.append({"result": None, "error": err_text(e)})
        outputs = []
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
        checks = []
        for a in seq.get("assertions", []):
            try:
                checks.append(env.run_env_assertion(EnvAssertion(**a), raise_assertion_error=False))
            except Exception as e:  # noqa: BLE001
                checks.append(err_text(e))
        out.update(hashes(env))
        out["outputs"] = outputs
        if seq.get("env_calls"):
            out["env_outputs"] = env_outputs
        if seq.get("assertions"):
            out["assertions"] = checks
        results.append(out)
    return results


def params(toolkit):
    return {
        name: [[p.name, p.default is inspect.Parameter.empty] for p in inspect.signature(fn).parameters.values()]
        for name, fn in toolkit.tools.items()
    }


def schema():
    reset_process_state()
    env = fresh_env()
    return {
        "policy": env.policy,
        "initial_db_hash": env.get_db_hash(),
        "initial_user_db_hash": env.get_user_db_hash(),
        "initial_db": json.loads(json.dumps(env.tools.db.model_dump(), default=str)),
        "initial_user_db": json.loads(json.dumps(env.user_tools.db.model_dump(), default=str)),
        "tools": [t.openai_schema for t in env.get_tools()],
        "user_tools": [t.openai_schema for t in env.get_user_tools()],
        "mutating": {
            name: env._is_mutating_tool(name)
            for name in list(env.tools.tools) + list(env.user_tools.tools)
        },
        "agent_tool_params": params(env.tools),
        "user_tool_params": params(env.user_tools),
    }


def prompts():
    from tau2.agent.llm_agent import LLMAgent
    from tau2.orchestrator.orchestrator import DEFAULT_FIRST_AGENT_MESSAGE
    from tau2.user.user_simulator import UserSimulator

    env = fresh_env()
    agent = LLMAgent(tools=env.get_tools(), domain_policy=env.policy, llm="none")
    users, user_tools = {}, {}
    for task in all_tasks().values():
        tools = env.get_user_tools(include=task.user_tools) or None
        user_tools[task.id] = [t.name for t in tools or []]
        users[task.id] = UserSimulator(llm="none", instructions=task.user_scenario, tools=tools).system_prompt
    return {
        "agent_system": agent.system_prompt,
        "first_agent_message": DEFAULT_FIRST_AGENT_MESSAGE.content,
        "user_system": users,
        "user_tools": user_tools,
        "base_split": tenv.get_tasks_split()["base"],
        "get_tasks_default_ids": [t.id for t in tenv.get_tasks()],
    }


def upstream_grade(task, calls):
    """Upstream's reward for a trajectory of tool calls (one tool-call message
    per call, by its requestor, answered by the live environment), graded by
    EnvironmentEvaluator + ActionEvaluator (the only components in telecom
    reward bases). The predicted environment is rebuilt by upstream's own
    `set_state` replay."""
    from tau2.evaluator.evaluator_action import ActionEvaluator
    from tau2.evaluator.evaluator_env import EnvironmentEvaluator

    reset_process_state()
    env = fresh_env(task)
    trajectory = []
    for i, call in enumerate(calls):
        tc = ToolCall(id=f"g{i}", name=call["name"], arguments=call["arguments"],
                      requestor=call["requestor"])
        cls = UserMessage if call["requestor"] == "user" else AssistantMessage
        trajectory.append(cls(role=call["requestor"], content=None, tool_calls=[tc]))
        trajectory.append(env.get_response(tc))
    reset_process_state()
    return trajectory, *_rewards(task, trajectory, ActionEvaluator, EnvironmentEvaluator)


def _rewards(task, trajectory, ActionEvaluator, EnvironmentEvaluator):
    env_info = EnvironmentEvaluator.calculate_reward(
        environment_constructor=tenv.get_environment, task=task, full_trajectory=trajectory
    )
    act_info = ActionEvaluator.calculate_reward(task=task, full_trajectory=trajectory)
    basis = set(task.evaluation_criteria.reward_basis)
    reward = 1.0
    if basis & {"DB", "ENV_ASSERTION"}:
        reward *= env_info.reward
    if "ACTION" in basis:
        reward *= act_info.reward
    return reward, env_info, act_info


def grade(sequences):
    """`[{"id", "task", "calls"}]` -> upstream reward per trajectory."""
    tasks = all_tasks()
    out = {}
    for seq in sequences:
        _, reward, env_info, act_info = upstream_grade(tasks[seq["task"]], seq["calls"])
        out[seq["id"]] = {
            "reward": reward,
            "env_assertions": [c.met for c in env_info.env_assertions or []],
            "action_reward": act_info.reward,
        }
    return out


def gold_eval():
    """Upstream's own reward for each base task's gold actions."""
    from tau2.evaluator.evaluator_action import ActionEvaluator
    from tau2.evaluator.evaluator_env import EnvironmentEvaluator

    out = {}
    for task in tenv.get_tasks("base"):
        reset_process_state()
        env = fresh_env(task)
        trajectory = []
        for i, action in enumerate(task.evaluation_criteria.actions or []):
            tc = ToolCall(id=f"g{i}", name=action.name, arguments=action.arguments,
                          requestor=action.requestor)
            cls = UserMessage if action.requestor == "user" else AssistantMessage
            trajectory.append(cls(role=action.requestor, content=None, tool_calls=[tc]))
            trajectory.append(env.get_response(tc))
        reset_process_state()
        env_info = EnvironmentEvaluator.calculate_reward(
            environment_constructor=tenv.get_environment, task=task, full_trajectory=trajectory
        )
        act_info = ActionEvaluator.calculate_reward(task=task, full_trajectory=trajectory)
        basis = set(task.evaluation_criteria.reward_basis)
        reward = 1.0
        if basis & {"DB", "ENV_ASSERTION"}:
            reward *= env_info.reward
        if "ACTION" in basis:
            reward *= act_info.reward
        out[task.id] = {
            "reward": reward,
            "env_reward": env_info.reward,
            "action_reward": act_info.reward,
            "db_match": env_info.db_check.db_match if env_info.db_check else None,
            "env_assertions": [c.met for c in env_info.env_assertions or []],
            "tool_outputs": [{"content": m.content, "error": m.error}
                             for m in trajectory if m.role == "tool"],
        }
    return out


def main():
    mode = sys.argv[1]
    if mode in ("replay", "grade"):
        with open(sys.argv[2]) as fp:
            out = {"replay": replay, "grade": grade}[mode](json.load(fp))
        target = sys.argv[3]
    else:
        out = {"schema": schema, "prompts": prompts, "gold-eval": gold_eval}[mode]()
        target = sys.argv[2]
    with open(target, "w") as fp:
        json.dump(out, fp, indent=1)


if __name__ == "__main__":
    main()
