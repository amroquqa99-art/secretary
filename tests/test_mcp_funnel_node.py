"""The public MCP node script and its infra-watchdog check.

Both run under bash with fake `docker` and `tailscale` binaries first on PATH.
The fake docker keeps the container's state in a JSON file and runs
`docker exec <name> tailscale ...` against a serve table of its own, so tests
can tell the public node's configuration apart from the host's.
"""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest

from tests.test_infra_routes import CURL_FAKE, PGREP_FAKE

pytestmark = pytest.mark.unit

REPO_ROOT = Path(__file__).resolve().parents[1]
NODE = REPO_ROOT / "scripts" / "mcp-funnel-node.sh"
WATCHDOG = REPO_ROOT / "scripts" / "infra-watchdog.sh"
HOST = "lifeos-mcp.example.ts.net"
TARGET = "unix:/sock/mcp.sock"

DOCKER_FAKE = '''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_ACTIONS"], "a") as f:
    f.write("docker " + " ".join(args) + "\\n")
path = os.environ["FAKE_NODE"]
try:
    node = json.load(open(path))
except OSError:
    node = {"container": "missing", "backend": "Running", "serve": {}}
def save():
    json.dump(node, open(path, "w"))
if args[0] == "inspect":
    if node["container"] == "missing":
        sys.exit(1)
    print("true" if node["container"] == "running" else "false")
    sys.exit(0)
if args[0] == "run":
    node["container"] = "running"; save(); sys.exit(0)
if args[0] == "start":
    node["container"] = "running"; save(); sys.exit(0)
if args[0] != "exec" or node["container"] != "running":
    sys.exit(1)
ts = args[2:]
assert ts[0] == "tailscale"
ts = ts[1:]
if ts[:2] == ["status", "--json"]:
    print(json.dumps({"BackendState": node["backend"]})); sys.exit(0)
if ts[:2] == ["serve", "status"]:
    print(json.dumps(node["serve"])); sys.exit(0)
if ts[:2] == ["serve", "reset"]:
    node["serve"] = {}; save(); sys.exit(0)
if ts[0] == "funnel" and "--bg" in ts:
    if os.environ.get("FAKE_APPLY_BROKEN"):
        sys.exit(1)
    port = next(a.split("=", 1)[1] for a in ts if a.startswith("--https="))
    hp = os.environ["FAKE_HOST"] + ":" + port
    serve = node["serve"]
    serve.setdefault("TCP", {})[port] = {"HTTPS": True}
    serve.setdefault("Web", {}).setdefault(hp, {"Handlers": {}})["Handlers"]["/"] = {"Proxy": ts[-1]}
    serve.setdefault("AllowFunnel", {})[hp] = True
    save(); sys.exit(0)
if ts[0] == "login":
    print("To authenticate, visit:\\n\\n\\thttps://login.tailscale.com/a/synthetic123\\n"); sys.exit(0)
sys.exit(1)
'''

# The host's own tailscale: the watchdog's route checks run against it.
HOST_TAILSCALE_FAKE = '''#!/usr/bin/env bash
echo "host-tailscale $*" >> "$FAKE_ACTIONS"
case "$*" in
  "serve status --json") echo '{"Web": {"h:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8000"}}}}}' ;;
esac
exit 0
'''


@pytest.fixture
def env(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name, body in (
        ("docker", DOCKER_FAKE),
        ("tailscale", HOST_TAILSCALE_FAKE),
        ("curl", CURL_FAKE),
        ("pgrep", PGREP_FAKE),
    ):
        p = bin_dir / name
        p.write_text(body)
        p.chmod(0o755)
    env_file = tmp_path / ".env"
    env_file.write_text("TELEGRAM_BOT_TOKEN=xtoken\nTELEGRAM_CHAT_ID=123\n")
    node_file = tmp_path / "node.json"
    actions = tmp_path / "actions.log"

    class Env:
        pass

    e = Env()
    e.base = {
        "PATH": f"{bin_dir}:{os.environ['PATH']}",
        "FAKE_ACTIONS": str(actions),
        "FAKE_NODE": str(node_file),
        "FAKE_HOST": HOST,
        "LIFEOS_MCP_HTTP_UDS": str(tmp_path / "sock" / "mcp.sock"),
        "LIFEOS_MCP_FUNNEL_STATE_DIR": str(tmp_path / "ts-state"),
        "LIFEOS_MCP_FUNNEL_WAIT_SECONDS": "0",
        "LIFEOS_TAILSCALE_ROUTES_FILE": str(tmp_path / "routes.local"),
        "LIFEOS_INFRA_STATE_DIR": str(tmp_path / "state"),
        "ENV_FILE": str(env_file),
        "LIFEOS_WATCHDOG_CURL": str(bin_dir / "curl"),
        "HOME": str(tmp_path),
    }

    def run(script, *args, extra=None):
        return subprocess.run(
            ["bash", str(script), *args], env={**e.base, **(extra or {})},
            capture_output=True, text=True, timeout=30,
        )

    def set_node(**fields):
        node = e.node_state()
        node.update(fields)
        node_file.write_text(json.dumps(node))

    e.run_node = lambda *a, extra=None: run(NODE, *a, extra=extra)
    e.watch = lambda extra=None: run(WATCHDOG, extra={"LIFEOS_MCP_FUNNEL_NODE": "true", **(extra or {})})
    e.node_state = lambda: json.loads(node_file.read_text()) if node_file.exists() else {
        "container": "missing", "backend": "Running", "serve": {}}
    e.set_node = set_node
    e.log = lambda: actions.read_text().splitlines() if actions.exists() else []
    e.telegrams = lambda: [a for a in e.log() if "api.telegram.org" in a]
    e.watchdog_log = lambda: (tmp_path / "state" / "infra-watchdog.log").read_text()
    return e


def _exact(serve: dict) -> bool:
    hp = f"{HOST}:443"
    return serve == {
        "TCP": {"443": {"HTTPS": True}},
        "Web": {hp: {"Handlers": {"/": {"Proxy": TARGET}}}},
        "AllowFunnel": {hp: True},
    }


def test_up_creates_an_isolated_container_and_publishes_only_the_socket(env):
    r = env.run_node("up")
    assert r.returncode == 0, r.stderr
    run = next(a for a in env.log() if a.startswith("docker run"))
    assert "--network bridge" in run
    assert "--network host" not in run
    assert ":/sock:ro" in run
    assert "TS_USERSPACE=true" in run
    assert _exact(env.node_state()["serve"])
    assert env.run_node("check").returncode == 0


def test_up_starts_a_stopped_container_instead_of_creating_one(env):
    env.set_node(container="stopped")
    assert env.run_node("up").returncode == 0
    assert any(a.startswith("docker start") for a in env.log())
    assert not any(a.startswith("docker run") for a in env.log())


def test_up_refuses_without_the_public_socket_setting(env):
    r = env.run_node("up", extra={"LIFEOS_MCP_HTTP_UDS": ""})
    assert r.returncode == 1
    assert "LIFEOS_MCP_HTTP_UDS" in r.stderr
    assert not any(a.startswith("docker run") for a in env.log())


@pytest.mark.parametrize("extra_handler", ["/oauth/authorize", "/oauth/clients", "/admin"])
def test_check_rejects_an_extra_handler_and_apply_removes_it(env, extra_handler):
    env.run_node("up")
    node = env.node_state()
    node["serve"]["Web"][f"{HOST}:443"]["Handlers"][extra_handler] = {"Proxy": "http://127.0.0.1:8765"}
    env.set_node(serve=node["serve"])
    assert env.run_node("check").returncode == 1
    assert env.run_node("apply").returncode == 0
    assert _exact(env.node_state()["serve"])


def test_check_rejects_a_second_served_port_or_missing_funnel(env):
    env.run_node("up")
    serve = env.node_state()["serve"]
    serve["TCP"]["8443"] = {"HTTPS": True}
    serve["Web"][f"{HOST}:8443"] = {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8000"}}}
    env.set_node(serve=serve)
    assert env.run_node("check").returncode == 1
    env.run_node("apply")
    serve = env.node_state()["serve"]
    serve["AllowFunnel"] = {}
    env.set_node(serve=serve)
    assert env.run_node("check").returncode == 1


@pytest.mark.parametrize("backend", ["Stopped", "Starting", ""])
def test_check_requires_a_running_backend(env, backend):
    env.run_node("up")
    env.set_node(backend=backend)
    assert env.run_node("check").returncode == 1


def test_check_fails_when_the_container_is_not_running(env):
    env.run_node("up")
    env.set_node(container="stopped")
    assert env.run_node("check").returncode == 1


def test_logged_out_node_reports_3_and_publishes_nothing(env):
    env.set_node(container="running", backend="NeedsLogin")
    r = env.run_node("apply")
    assert r.returncode == 3
    assert env.node_state()["serve"] == {}
    assert env.run_node("check").returncode == 3


def test_login_prints_the_login_url(env):
    env.set_node(container="running", backend="NeedsLogin")
    r = env.run_node("login")
    assert r.stdout.strip() == "https://login.tailscale.com/a/synthetic123"


def test_apply_that_does_not_take_effect_fails(env):
    env.set_node(container="running")
    r = env.run_node("apply", extra={"FAKE_APPLY_BROKEN": "1"})
    assert r.returncode == 1
    assert "not published exactly" in r.stderr


def test_watchdog_brings_up_a_missing_node_without_alerting(env):
    env.watch()
    assert _exact(env.node_state()["serve"])
    assert "mcp-funnel: node was down or not publishing exactly; restored" in env.watchdog_log()
    assert env.telegrams() == []


def test_watchdog_leaves_a_published_node_alone(env):
    env.run_node("up")
    before = len(env.log())
    env.watch()
    assert not any(a.startswith(("docker run", "docker start")) or " funnel " in a or "serve reset" in a
                   for a in env.log()[before:])
    assert "mcp-funnel: published" in env.watchdog_log()


def test_watchdog_alerts_when_the_node_is_logged_out(env):
    env.set_node(container="running", backend="NeedsLogin")
    env.watch()
    assert len(env.telegrams()) == 1
    assert "mcp-funnel: node logged out" in env.watchdog_log()


def test_watchdog_alerts_when_the_node_cannot_be_restored(env):
    env.set_node(container="running")
    env.watch({"FAKE_APPLY_BROKEN": "1"})
    assert len(env.telegrams()) == 1
    assert "could not be restored" in env.watchdog_log()


def test_login_and_restore_alerts_have_separate_cooldowns(env):
    env.set_node(container="running", backend="NeedsLogin")
    env.watch()
    env.set_node(backend="Running")
    env.watch({"FAKE_APPLY_BROKEN": "1"})
    assert len(env.telegrams()) == 2


def test_watchdog_skips_the_node_unless_opted_in(env):
    env.watch({"LIFEOS_MCP_FUNNEL_NODE": "false"})
    assert not any(a.startswith("docker") for a in env.log())
