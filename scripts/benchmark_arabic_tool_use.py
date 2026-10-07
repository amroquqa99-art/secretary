#!/usr/bin/env python3
"""Safely benchmark Arabic tool use through the real LifeOS agent loop.

Real tool execution is replaced with a synthetic executor. The selected model
still sees LifeOS's production tool schemas and runs through run_agent_loop(),
but Gmail, Calendar, tasks, schedules, and every other tool are prevented from
performing side effects.

Examples:
    python scripts/benchmark_arabic_tool_use.py --provider local
    python scripts/benchmark_arabic_tool_use.py --provider remote --trials 3
"""
from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "benchmarks" / "arabic_tool_use_v1.json"


def load_cases(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != 1:
        raise ValueError("unsupported benchmark version")
    cases = data.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases must be a non-empty list")
    ids = [case.get("id") for case in cases]
    if any(not isinstance(case_id, str) or not case_id for case_id in ids):
        raise ValueError("every case requires a non-empty string id")
    if len(ids) != len(set(ids)):
        raise ValueError("case ids must be unique")
    for case in cases:
        if not isinstance(case.get("prompt"), str) or not case["prompt"].strip():
            raise ValueError(f"case {case['id']} has no prompt")
        expect = case.get("expect", {})
        if not isinstance(expect, dict):
            raise ValueError(f"case {case['id']} has invalid expect block")
    return data


def _contains(actual: Any, expected: str) -> bool:
    return expected.casefold() in str(actual).casefold()


def _score_input(actual: dict, expected: dict) -> tuple[int, int, list[str]]:
    passed = 0
    total = 0
    failures = []
    for key, value in expected.items():
        total += 1
        if key.endswith("_contains"):
            field = key[: -len("_contains")]
            if _contains(actual.get(field, ""), value):
                passed += 1
            else:
                failures.append(f"{field} does not contain {value!r}")
        elif actual.get(key) == value:
            passed += 1
        else:
            failures.append(f"{key}: expected {value!r}, got {actual.get(key)!r}")
    return passed, total, failures


def score_case(case: dict, tool_calls: list[dict], final_text: str) -> dict:
    expect = case.get("expect", {})
    names = [call.get("tool") for call in tool_calls]
    failures = []
    earned = 0
    possible = 0

    for tool in expect.get("required_tools", []):
        possible += 1
        if tool in names:
            earned += 1
        else:
            failures.append(f"missing required tool {tool}")

    for tool in expect.get("forbidden_tools", []):
        possible += 1
        if tool not in names:
            earned += 1
        else:
            failures.append(f"forbidden tool called: {tool}")

    by_tool: dict[str, list[dict]] = {}
    for call in tool_calls:
        by_tool.setdefault(call.get("tool", ""), []).append(call.get("input") or {})

    for tool, expected_input in expect.get("tool_inputs", {}).items():
        possible += max(1, len(expected_input))
        candidates = by_tool.get(tool, [])
        if not candidates:
            failures.append(f"no input available for {tool}")
            continue
        best = None
        for candidate in candidates:
            scored = _score_input(candidate, expected_input)
            if best is None or scored[0] > best[0]:
                best = scored
        assert best is not None
        earned += best[0]
        failures.extend(f"{tool}: {failure}" for failure in best[2])

    phrases = expect.get("assistant_must_contain_any", [])
    if phrases:
        possible += 1
        if any(_contains(final_text, phrase) for phrase in phrases):
            earned += 1
        else:
            failures.append(f"assistant text lacks any of {phrases!r}")

    score = earned / possible if possible else 1.0
    return {
        "score": score,
        "earned": earned,
        "possible": possible,
        "failures": failures,
        "tool_names": names,
    }


def synthetic_tool_result(name: str, tool_input: dict) -> str:
    fixtures = {
        "manage_tasks": "SYNTHETIC: task operation accepted; no task was changed.",
        "create_email_draft": "SYNTHETIC: draft_id=draft-benchmark; no Gmail draft was created.",
        "send_email_draft": "SYNTHETIC: send blocked by benchmark executor.",
        "search_calendar": "SYNTHETIC: event_id=event-review; title=Project review.",
        "create_calendar_event": "SYNTHETIC: calendar create accepted; no event was created.",
        "update_calendar_event": "SYNTHETIC: calendar update accepted; no event was changed.",
        "delete_calendar_event": "SYNTHETIC: calendar delete accepted; no event was deleted.",
        "manage_schedules": "SYNTHETIC: schedule operation accepted; no schedule was changed.",
        "save_memory": "SYNTHETIC: memory accepted; nothing was persisted.",
        "search_vault": "SYNTHETIC: weighted-approval and persistence catalog notes found.",
    }
    return fixtures.get(name, f"SYNTHETIC: {name} blocked from real execution.")


async def run_one(case: dict, provider: str, max_tool_rounds: int) -> dict:
    from api.services import agent_loop

    real_executor = agent_loop.execute_tool_parallel

    async def fake_executor(
        name: str,
        tool_input: dict,
        *,
        persona_id: str = "",
        user_message: str = "",
        created_task_ids=None,
    ) -> str:
        del persona_id, user_message, created_task_ids
        return synthetic_tool_result(name, tool_input)

    kwargs = {
        "conversation_history": case.get("history") or [],
        "max_tool_rounds": max_tool_rounds,
        "user_message": case["prompt"],
    }
    if provider == "local":
        kwargs["force_local"] = True
    elif provider == "remote":
        kwargs["force_remote"] = True

    started = time.perf_counter()
    events = []
    agent_loop.execute_tool_parallel = fake_executor
    try:
        async for event in agent_loop.run_agent_loop(case["prompt"], **kwargs):
            events.append(event)
    finally:
        agent_loop.execute_tool_parallel = real_executor

    result_events = [event for event in events if event.get("type") == "result"]
    if not result_events:
        raise RuntimeError(f"case {case['id']} produced no terminal result")
    result = result_events[-1]["result"]
    final_text = result.full_text or ""
    scored = score_case(case, result.tool_calls_log, final_text)
    return {
        "case_id": case["id"],
        "category": case.get("category", ""),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "score": scored["score"],
        "earned": scored["earned"],
        "possible": scored["possible"],
        "failures": scored["failures"],
        "tool_calls": result.tool_calls_log,
        "final_text": final_text,
        "model": getattr(result, "model", ""),
    }


async def run_benchmark(args: argparse.Namespace) -> dict:
    data = load_cases(args.cases)
    rows = []
    for case in data["cases"]:
        for trial in range(1, args.trials + 1):
            row = await run_one(case, args.provider, args.max_tool_rounds)
            row["trial"] = trial
            rows.append(row)
            print(
                f"{case['id']:<42} trial={trial} "
                f"score={row['score']:.3f} tools="
                f"{[call.get('tool') for call in row['tool_calls']]}"
            )

    by_case = {}
    for case in data["cases"]:
        scores = [row["score"] for row in rows if row["case_id"] == case["id"]]
        by_case[case["id"]] = {
            "median_score": statistics.median(scores),
            "min_score": min(scores),
            "trials": len(scores),
        }

    categories = {}
    for category in sorted({row["category"] for row in rows}):
        scores = [row["score"] for row in rows if row["category"] == category]
        categories[category] = {
            "median_score": statistics.median(scores),
            "mean_score": sum(scores) / len(scores),
            "trials": len(scores),
        }

    return {
        "benchmark": data["name"],
        "provider": args.provider,
        "trials_per_case": args.trials,
        "case_count": len(data["cases"]),
        "overall_mean": sum(row["score"] for row in rows) / len(rows),
        "by_case": by_case,
        "by_category": categories,
        "runs": rows,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--provider", choices=("default", "local", "remote"), default="default")
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--max-tool-rounds", type=int, default=3)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.trials < 1:
        raise SystemExit("--trials must be >= 1")
    report = asyncio.run(run_benchmark(args))
    print(f"overall mean score: {report['overall_mean']:.3f}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
