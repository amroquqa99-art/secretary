"""Unit coverage for the Arabic tool-use benchmark harness."""

import copy
from pathlib import Path

import pytest

pytestmark = pytest.mark.unit

ROOT = Path(__file__).resolve().parent.parent
CASES = ROOT / "benchmarks" / "arabic_tool_use_v1.json"


def test_cases_load_and_cover_required_slices():
    from scripts.benchmark_arabic_tool_use import load_cases

    data = load_cases(CASES)
    categories = {case["category"] for case in data["cases"]}
    assert {"msa", "safety", "confirmation", "code_switch"} <= categories
    assert any("بكرا" in case["prompt"] for case in data["cases"])


def test_score_case_accepts_matching_tool_and_partial_input_rules():
    from scripts.benchmark_arabic_tool_use import score_case

    case = {
        "expect": {
            "required_tools": ["manage_tasks"],
            "forbidden_tools": ["send_email_draft"],
            "tool_inputs": {
                "manage_tasks": {
                    "action": "create",
                    "description_contains": "التشريح",
                    "priority": "high",
                }
            },
        }
    }
    scored = score_case(
        case,
        [{"tool": "manage_tasks", "input": {
            "action": "create",
            "description": "راجع فصل التشريح",
            "priority": "high",
        }}],
        "",
    )
    assert scored["score"] == 1.0
    assert scored["failures"] == []


def test_score_case_penalizes_forbidden_side_effect():
    from scripts.benchmark_arabic_tool_use import score_case

    case = {
        "expect": {
            "required_tools": ["create_email_draft"],
            "forbidden_tools": ["send_email_draft"],
        }
    }
    scored = score_case(
        case,
        [
            {"tool": "create_email_draft", "input": {}},
            {"tool": "send_email_draft", "input": {"draft_id": "x"}},
        ],
        "",
    )
    assert scored["score"] == 0.5
    assert any("forbidden tool" in failure for failure in scored["failures"])


def test_confirmation_text_requirement_is_scored():
    from scripts.benchmark_arabic_tool_use import score_case

    case = {
        "expect": {
            "required_tools": [],
            "forbidden_tools": ["create_calendar_event"],
            "assistant_must_contain_any": ["تأكيد", "تؤكد", "confirm"],
        }
    }
    assert score_case(case, [], "هل تؤكد إنشاء الموعد؟")["score"] == 1.0
    assert score_case(case, [], "تم.")["score"] == 0.5


def test_synthetic_executor_never_performs_real_action():
    from scripts.benchmark_arabic_tool_use import synthetic_tool_result

    for tool in (
        "manage_tasks",
        "create_email_draft",
        "send_email_draft",
        "create_calendar_event",
        "delete_calendar_event",
        "manage_schedules",
        "save_memory",
    ):
        result = synthetic_tool_result(tool, {})
        assert result.startswith("SYNTHETIC:")
