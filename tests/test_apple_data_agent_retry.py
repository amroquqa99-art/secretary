"""Tests for retry_with_backoff in scripts/apple_data_agent.sh.

The agent script runs its whole pipeline at top level and only proceeds on
macOS, so these tests extract the real function definitions (and the real
Step 3 rsync gate) from the script text and run them in bash with stubbed
commands. Retry delays are zero so no test sleeps.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
AGENT_SCRIPT = REPO_ROOT / "scripts" / "apple_data_agent.sh"


def _function_source(text: str, name: str) -> str:
    match = re.search(rf"^{name}\(\) \{{\n.*?^\}}\n", text, re.M | re.S)
    assert match, f"{name}() not found in {AGENT_SCRIPT.name}"
    return match.group(0)


def _rsync_gate_source(text: str) -> str:
    match = re.search(
        r'^if retry_with_backoff "Rsync" .*?^fi\n', text, re.M | re.S
    )
    assert match, f"Rsync gate not found in {AGENT_SCRIPT.name}"
    return match.group(0)


def _run(tmp_path: Path, body: str) -> subprocess.CompletedProcess:
    text = AGENT_SCRIPT.read_text(encoding="utf-8")
    log_file = tmp_path / "agent.log"
    counter = tmp_path / "attempts"
    telegram_calls = tmp_path / "telegram_calls"
    script = "\n".join(
        [
            "set -euo pipefail",
            f'LOG_FILE="{log_file}"',
            f'COUNTER="{counter}"',
            f'TELEGRAM_CALLS="{telegram_calls}"',
            'RETRY_DELAYS="0,0,0"',
            'LINUX_SERVER="test-server.example"',
            'EXPORT_DIR="/tmp/exports/"',
            # Stub: records every call instead of hitting the network,
            # one call per line in the counter so tests can count calls.
            'send_telegram() { echo "$1" >> "${TELEGRAM_CALLS}"; echo "---" >> "${COUNTER}.telegram"; }',
            _function_source(text, "log"),
            _function_source(text, "retry_with_backoff"),
            # Fails with exit 7 on every call.
            'always_fail() { echo x >> "${COUNTER}"; return 7; }',
            # Fails twice, then succeeds.
            'fail_then_succeed() {',
            '    echo x >> "${COUNTER}"',
            '    (( $(wc -l < "${COUNTER}") >= 3 ))',
            '}',
            body,
        ]
    )
    return subprocess.run(
        ["bash", "-c", script], capture_output=True, text=True, timeout=30
    )


@pytest.mark.unit
def test_all_attempts_failing_returns_last_nonzero_exit(tmp_path: Path):
    result = _run(
        tmp_path,
        'rc=0; retry_with_backoff "Probe" "${RETRY_DELAYS}" always_fail || rc=$?\n'
        'echo "rc=${rc}"',
    )

    assert result.returncode == 0, result.stderr
    assert "rc=7" in result.stdout
    assert "Probe: all 4 attempts failed (final exit 7)" in result.stdout
    assert "succeeded" not in result.stdout
    assert (tmp_path / "attempts").read_text().count("x") == 4


@pytest.mark.unit
def test_failing_then_succeeding_returns_zero(tmp_path: Path):
    result = _run(
        tmp_path,
        'rc=0; retry_with_backoff "Probe" "${RETRY_DELAYS}" fail_then_succeed || rc=$?\n'
        'echo "rc=${rc}"',
    )

    assert result.returncode == 0, result.stderr
    assert "rc=0" in result.stdout
    assert "Probe: attempt 1/4 failed (exit 1)" in result.stdout
    assert "Probe: succeeded on attempt 3/4" in result.stdout
    assert (tmp_path / "attempts").read_text().count("x") == 3


@pytest.mark.unit
def test_rsync_gate_exits_nonzero_and_skips_import_when_every_attempt_fails(
    tmp_path: Path,
):
    text = AGENT_SCRIPT.read_text(encoding="utf-8")
    result = _run(
        tmp_path,
        "run_rsync() { always_fail; }\n"
        + _rsync_gate_source(text)
        + 'echo "Step 4: Triggering import on Linux server..."',
    )

    assert result.returncode == 1
    assert "Rsync: FAILED after retries" in result.stdout
    assert "Rsync: OK" not in result.stdout
    assert "Step 4" not in result.stdout


@pytest.mark.unit
def test_rsync_gate_alerts_telegram_on_failure_naming_host_and_exit_code(
    tmp_path: Path,
):
    text = AGENT_SCRIPT.read_text(encoding="utf-8")
    result = _run(
        tmp_path,
        "run_rsync() { always_fail; }\n" + _rsync_gate_source(text),
    )

    assert result.returncode == 1
    call_count = (tmp_path / "attempts.telegram").read_text().count("---")
    assert call_count == 1, call_count
    telegram_calls = (tmp_path / "telegram_calls").read_text()
    assert "test-server.example" in telegram_calls
    assert "exit 7" in telegram_calls


@pytest.mark.unit
def test_rsync_gate_does_not_alert_telegram_on_success(tmp_path: Path):
    text = AGENT_SCRIPT.read_text(encoding="utf-8")
    result = _run(
        tmp_path,
        "run_rsync() { :; }\n" + _rsync_gate_source(text),
    )

    assert result.returncode == 0
    assert "Rsync: OK" in result.stdout
    assert not (tmp_path / "telegram_calls").exists()
