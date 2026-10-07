"""E7 -- Pebble task filing: assignee and title accuracy against real Jev.

Scores the production `JevPebbleClassifier` followed by `validate_plan` (the
same path a captured Pebble note takes) over the committed, synthetic-only
labeled set `scripts/jev_eval/cases/pebble_filing_cases.jsonl`. Each line is
`{utterance, expected_title, expected_assignee, category}`, where
`expected_assignee` is `none`, `me`, or an agent executor tag, and a null
`expected_title` marks a note that asks for nothing to be filed.

For each case the first filed action is compared with the labels:

  - assignee: the action's assignee/executor tag, or `none` when it carries
    none or nothing was filed;
  - title: equal to the expected title after case-folding, whitespace
    normalization and dropping trailing punctuation; a capture that files
    nothing has no title, which is correct only for a null expected title.

A false agent assignment is any filed agent tag the label does not name; a
false filing is anything filed for a note labeled to file nothing (the
`journal` and `asr_journal` categories, plus a few traps). The `asr`
category holds filing requests whose request wording speech recognition
misheard ("at a desk to ..." for "add a task to ..."); `asr_journal` holds
notes that use such words in their ordinary meaning. Prints per-category accuracy, the
filed-correctly rate on notes that ask for a filing, both counters, and
(with `--show-failures`) each miss. Only the synthetic utterances in the labeled
set are sent to TypeSafe. Cost is a fraction of a cent per run.

    ~/.venvs/lifeos/bin/python scripts/jev_eval/e7_pebble_filing.py \
        --env-file /path/to/LifeOS/.env --show-failures
"""
import argparse
import asyncio
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

CASES = Path(__file__).resolve().parent / "cases" / "pebble_filing_cases.jsonl"
RECORDED_AT = "2030-01-01T10:00:00Z"


def normalize_title(value) -> str:
    if not isinstance(value, str):
        return ""
    return re.sub(r"\s+", " ", value).strip().rstrip(".,;:!?—- ").casefold()


def load_cases(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


class CountingClient:
    """Wraps a Jev client and counts calls per capture."""

    def __init__(self, inner):
        self.inner = inner
        self.calls = 0

    async def aask(self, state, questions):
        self.calls += 1
        return await self.inner.aask(state, questions)


def predicted(actions) -> tuple[str, str | None]:
    from api.services.agent_board import AGENT_EXECUTOR_TAGS, ASSIGNEE_TAGS

    if not actions:
        return "none", None
    first = actions[0]
    if first.kind == "schedule" and first.executor:
        return first.executor, first.title
    assignees = [tag for tag in first.tags if tag in (*ASSIGNEE_TAGS, *AGENT_EXECUTOR_TAGS)]
    return (assignees[0] if assignees else "none"), first.title


async def run_case(case: dict, client, semaphore) -> dict:
    from api.services.pebble_capture import JevPebbleClassifier, PebbleCaptureError, validate_plan

    counting = CountingClient(client)
    async with semaphore:
        raw = await JevPebbleClassifier(client=counting).classify(case["utterance"], RECORDED_AT)
    try:
        actions = validate_plan(
            raw, transcript=case["utterance"], recorded_at=RECORDED_AT, jev_classified=True,
        )
    except PebbleCaptureError:
        actions = []
    assignee, title = predicted(actions)
    return {
        **case,
        "predicted_assignee": assignee,
        "predicted_title": title,
        "filed": bool(actions),
        "jev_calls": counting.calls,
        "assignee_ok": assignee == case["expected_assignee"],
        "title_ok": (
            title is None if case["expected_title"] is None
            else title is not None and normalize_title(title) == normalize_title(case["expected_title"])
        ),
    }


def report(results: list[dict]) -> str:
    from api.services.agent_board import AGENT_EXECUTOR_TAGS

    by_category: dict[str, list[dict]] = defaultdict(list)
    for result in results:
        by_category[result["category"]].append(result)
    lines = [f"{'category':<14}{'n':>4}{'assignee':>11}{'title':>9}{'filed':>8}"]

    def row(name, rows):
        n = len(rows)
        assignee = sum(r["assignee_ok"] for r in rows) / n
        title = sum(r["title_ok"] for r in rows) / n
        filed = sum(r["filed"] for r in rows) / n
        return f"{name:<14}{n:>4}{assignee:>11.1%}{title:>9.1%}{filed:>8.1%}"

    for category in sorted(by_category):
        lines.append(row(category, by_category[category]))
    lines.append(row("OVERALL", results))
    false_agents = [
        r for r in results
        if r["predicted_assignee"] in AGENT_EXECUTOR_TAGS
        and r["predicted_assignee"] != r["expected_assignee"]
    ]
    calls = [r["jev_calls"] for r in results]
    lines.append(f"false agent assignments: {len(false_agents)}")
    should_file = [r for r in results if r["expected_title"] is not None]
    filed_ok = sum(r["filed"] for r in should_file) / len(should_file) if should_file else 0.0
    false_filings = [r for r in results if r["expected_title"] is None and r["filed"]]
    journal = [r for r in results if r["category"] == "journal"]
    lines.append(f"filed correctly (notes asking for a filing): {filed_ok:.1%} of {len(should_file)}")
    lines.append(
        f"false filings: {len(false_filings)} "
        f"(journal: {sum(r['filed'] for r in journal)} of {len(journal)})"
    )
    lines.append(f"Jev calls per capture: min {min(calls)} max {max(calls)} mean {sum(calls) / len(calls):.2f}")
    return "\n".join(lines)


async def main_async(args) -> list[dict]:
    from api.services.jev_client import JevClient

    cases = load_cases(Path(args.cases))
    for case in cases:
        expected = case["expected_title"]
        if expected is not None and expected.casefold() not in case["utterance"].casefold():
            raise SystemExit(f"label is not a literal span of its utterance: {case['utterance']!r}")
    client = JevClient()
    semaphore = asyncio.Semaphore(args.concurrency)
    return await asyncio.gather(*(run_case(case, client, semaphore) for case in cases))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--cases", default=str(CASES))
    parser.add_argument("--env-file", help="dotenv file providing TYPESAFE_API_KEY and LIFEOS_* settings")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--show-failures", action="store_true")
    parser.add_argument("--out", help="write per-case results as JSONL to this path")
    args = parser.parse_args()
    if args.env_file:
        from dotenv import load_dotenv

        load_dotenv(args.env_file)
    from config.settings import settings

    if not settings.jev_configured:
        raise SystemExit("TYPESAFE_API_KEY is not configured")
    results = asyncio.run(main_async(args))
    print(report(results))
    if args.show_failures:
        for r in results:
            if not (r["assignee_ok"] and r["title_ok"]):
                print(
                    f"- [{r['category']}] {r['utterance']!r}\n"
                    f"    assignee {r['predicted_assignee']!r} (want {r['expected_assignee']!r}), "
                    f"title {r['predicted_title']!r} (want {r['expected_title']!r})"
                )
    if args.out:
        Path(args.out).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in results))


if __name__ == "__main__":
    main()
