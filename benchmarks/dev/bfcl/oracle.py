#!/usr/bin/env python3
"""Oracle for dvergr's BFCL transcription: upstream's own code, run verbatim.

The only Python in the BFCL tier, and never needed to RUN the benchmark. It
answers two questions about a pinned gorilla checkout:

  tools  <category>...   the provider tool specs upstream compiles for every
                         task (language pre-processing + convert_to_tool,
                         Anthropic style), one JSON object per line
  check                  verdicts of upstream's ast_checker / relevance logic
                         for cases read from stdin, one JSON object per line:
                         {"category", "id", "calls"} ->
                         {"id", "valid", "error_type", "error"}

Upstream's package pulls in every provider SDK on import. The checker itself
needs none of them, so `model_config` is stubbed and the helper functions of
`utils.py` / `model_handler/utils.py` are executed from their source text.
"""
import ast
import copy
import json
import re
import sys
import types
from enum import Enum
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() / "berkeley-function-call-leaderboard"
sys.path.insert(0, str(ROOT))


class _Config:
    underscore_to_dot = True  # tool names cannot contain "."


class _AnyModel(dict):
    def __missing__(self, key):
        return _Config()


stub = types.ModuleType("bfcl_eval.constants.model_config")
stub.MODEL_CONFIG_MAPPING = _AnyModel()
sys.modules["bfcl_eval.constants.model_config"] = stub

from bfcl_eval.constants.enums import Language  # noqa: E402
from bfcl_eval.constants.type_mappings import GORILLA_TO_OPENAPI  # noqa: E402
from bfcl_eval.eval_checker.ast_eval.ast_checker import ast_checker  # noqa: E402


def functions_of(path, names, namespace):
    """Execute the named top-level functions of `path` in `namespace`."""
    tree = ast.parse(Path(path).read_text())
    wanted = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
    assert {n.name for n in wanted} == set(names), (path, names)
    exec(compile(ast.Module(body=wanted, type_ignores=[]), str(path), "exec"), namespace)
    return namespace


class ModelStyle(Enum):
    OPENAI_COMPLETIONS = 1
    OPENAI_RESPONSES = 2
    MISTRAL = 3
    GOOGLE = 4
    OSSMODEL = 5
    ANTHROPIC = 6
    COHERE = 7
    AMAZON = 8
    NOVITA_AI = 9
    WRITER = 10


handler_ns = functions_of(
    ROOT / "bfcl_eval/model_handler/utils.py",
    ["_cast_to_openai_type", "convert_to_tool"],
    {"copy": copy, "re": re, "GORILLA_TO_OPENAPI": GORILLA_TO_OPENAPI, "ModelStyle": ModelStyle},
)
utils_ns = functions_of(
    ROOT / "bfcl_eval/utils.py",
    [
        "is_java",
        "is_js",
        "_get_language_specific_hint",
        "_func_doc_language_specific_pre_processing",
        "is_function_calling_format_output",
        "is_empty_output",
    ],
    {"json": json},
)


def load(category, answers=False):
    sub = "possible_answer/" if answers else ""
    path = ROOT / f"bfcl_eval/data/{sub}BFCL_v4_{category}.json"
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def tools(categories):
    for category in categories:
        for entry in load(category):
            try:
                functions = utils_ns["_func_doc_language_specific_pre_processing"](
                    copy.deepcopy(entry["function"]), category
                )
                compiled = handler_ns["convert_to_tool"](
                    functions, GORILLA_TO_OPENAPI, ModelStyle.ANTHROPIC
                )
                out = {"id": entry["id"], "tools": compiled}
            except Exception as e:  # a data fault upstream would crash on
                out = {"id": entry["id"], "exception": type(e).__name__}
            print(json.dumps(out, ensure_ascii=False))


def check():
    questions, answers = {}, {}
    for line in sys.stdin:
        case = json.loads(line)
        category = case["category"]
        if category not in questions:
            questions[category] = {e["id"]: e for e in load(category)}
            try:
                answers[category] = {e["id"]: e["ground_truth"] for e in load(category, True)}
            except FileNotFoundError:
                answers[category] = {}
        entry = questions[category][case["id"]]
        calls = case["calls"]
        try:
            if "relevance" in category:  # relevance and irrelevance
                contains_call = not utils_ns["is_empty_output"](calls)
                valid = (not contains_call) if "irrelevance" in category else contains_call
                result = {"valid": valid}
                if not valid:
                    result["error_type"] = (
                        "irrelevance_error:decoder_success"
                        if "irrelevance" in category
                        else "relevance_error:decoder_failed"
                    )
            elif not utils_ns["is_function_calling_format_output"](calls):
                result = {"valid": False, "error_type": "ast_decoder:decoder_wrong_output_format"}
            else:
                result = ast_checker(
                    entry["function"], calls, answers[category][case["id"]],
                    Language.PYTHON, category, "oracle",
                )
        except Exception as e:
            result = {"valid": False, "error_type": "exception:" + type(e).__name__, "error": [str(e)]}
        print(json.dumps({
            "case": case.get("case"),
            "id": case["id"],
            "valid": bool(result["valid"]),
            "error_type": None if result["valid"] else result.get("error_type"),
            "error": [e for e in result.get("error", []) if isinstance(e, str)],
        }, ensure_ascii=False))


if __name__ == "__main__":
    command = sys.argv[2]
    if command == "tools":
        tools(sys.argv[3:])
    elif command == "check":
        check()
    else:
        sys.exit("usage: oracle.py <gorilla-root> tools <category>... | check")
