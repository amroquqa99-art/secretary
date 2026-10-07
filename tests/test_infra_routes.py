"""Declared tailnet routes (setup-tailscale.sh) and the infra watchdog.

The scripts run under bash with fake `tailscale`, `curl`, `pgrep` and
`systemd-run` binaries first on PATH. Each fake appends its argv to
$FAKE_ACTIONS so tests assert exactly what was invoked. The fake `tailscale`
keeps a JSON route table so `serve status --json` reflects what was applied.
"""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest

pytestmark = pytest.mark.unit

REPO_ROOT = Path(__file__).resolve().parents[1]
SETUP = REPO_ROOT / "scripts" / "setup-tailscale.sh"
WATCHDOG = REPO_ROOT / "scripts" / "infra-watchdog.sh"
ENABLE_TIMER = REPO_ROOT / "scripts" / "enable-user-timer.sh"
LINGER = REPO_ROOT / "scripts" / "ensure-linger.sh"
SERVICE = REPO_ROOT / "config" / "systemd" / "user" / "lifeos-infra-watchdog.service"

HOST = "example-host.tailnet.ts.net"

TAILSCALE_FAKE = '''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]


def go_uint(text):
    # Go flag.UintVar: 0x... is hex, a leading 0 is octal, otherwise decimal.
    if text.lower().startswith("0x"):
        return int(text[2:], 16)
    if len(text) > 1 and text.startswith("0"):
        return int(text[1:], 8)
    return int(text)


with open(os.environ["FAKE_ACTIONS"], "a") as f:
    f.write("tailscale " + " ".join(args) + "\\n")
state_path = os.environ["FAKE_TS_STATE"]
try:
    state = json.load(open(state_path))
except OSError:
    state = {}
if args[:2] == ["serve", "reset"]:
    state = {}
elif args[:2] == ["serve", "status"] and "--json" in args:
    web, funnel = {}, {}
    for key, target in state.items():
        port, path = key.split("|", 1)
        if port == "funnel":
            funnel[os.environ["FAKE_TS_HOST"] + ":" + path] = True
            continue
        web.setdefault(os.environ["FAKE_TS_HOST"] + ":" + port, {"Handlers": {}})["Handlers"][path] = {"Proxy": target}
    print(json.dumps({"Web": web, "AllowFunnel": funnel}))
    sys.exit(0)
elif args[:2] == ["serve", "status"]:
    print("fake serve status")
    sys.exit(0)
elif args[0] == "funnel" and args[-1] == "off":
    port = str(go_uint(next(a.split("=", 1)[1] for a in args if a.startswith("--https="))))
    # Upstream semantics: turning funnel off removes the port's web handlers too.
    if not os.environ.get("FAKE_FUNNEL_STUCK"):
        for key in [k for k in state if k.startswith(port + "|") or k == "funnel|" + port]:
            state.pop(key)
elif args[0] in ("serve", "funnel"):
    port = str(go_uint(next(a.split("=", 1)[1] for a in args if a.startswith("--https="))))
    path = args[args.index("--set-path") + 1]
    target = args[-1]
    if port != os.environ.get("FAKE_TS_DROP_PORT"):
        state[port + "|" + path] = target
        if args[0] == "funnel":
            state["funnel|" + port] = True
json.dump(state, open(state_path, "w"))
'''

CURL_FAKE = '''#!/usr/bin/env bash
echo "curl $*" >> "$FAKE_ACTIONS"
case "$*" in
  *api.telegram.org*) exit "${FAKE_TG_RC:-0}" ;;
  *) exit "${FAKE_HEALTH_RC:-0}" ;;
esac
'''

PGREP_FAKE = '''#!/usr/bin/env bash
echo "pgrep $*" >> "$FAKE_ACTIONS"
[ "${FAKE_OBSIDIAN:-0}" = "1" ] && exit 0 || exit 1
'''

SYSTEMCTL_FAKE = '''#!/usr/bin/env bash
echo "systemctl $*" >> "$FAKE_ACTIONS"
case "$2" in
  daemon-reload) exit "${FAKE_DAEMON_RELOAD_RC:-0}" ;;
  enable) exit "${FAKE_ENABLE_RC:-0}" ;;
  is-active) echo active ;;
esac
exit 0
'''

LOGINCTL_FAKE = '''#!/usr/bin/env bash
echo "loginctl $*" >> "$FAKE_ACTIONS"
if [ "$1" = "show-user" ]; then
    [ "${FAKE_LINGER:-yes}" = "yes" ] && echo "Linger=yes" || echo "Linger=no"
fi
exit 0
'''

SYSTEMD_RUN_FAKE = '''#!/usr/bin/env bash
echo "systemd-run $*" >> "$FAKE_ACTIONS"
exit 0
'''


@pytest.fixture
def box(tmp_path):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name, body in (
        ("tailscale", TAILSCALE_FAKE),
        ("curl", CURL_FAKE),
        ("pgrep", PGREP_FAKE),
        ("systemd-run", SYSTEMD_RUN_FAKE),
        ("loginctl", LOGINCTL_FAKE),
        ("systemctl", SYSTEMCTL_FAKE),
    ):
        p = bin_dir / name
        p.write_text(body)
        p.chmod(0o755)
    env_file = tmp_path / ".env"
    env_file.write_text("TELEGRAM_BOT_TOKEN=xtoken\nTELEGRAM_CHAT_ID=123\n")
    routes = tmp_path / "routes.local"
    actions = tmp_path / "actions.log"
    state_dir = tmp_path / "state"
    ts_state = tmp_path / "ts-state.json"

    class Box:
        pass

    b = Box()
    b.routes, b.actions, b.state_dir, b.ts_state = routes, actions, state_dir, ts_state

    def run(script, extra=None):
        env = {
            "PATH": f"{bin_dir}:{os.environ['PATH']}",
            "FAKE_ACTIONS": str(actions),
            "FAKE_TS_STATE": str(ts_state),
            "FAKE_TS_HOST": HOST,
            "LIFEOS_TAILSCALE_ROUTES_FILE": str(routes),
            "LIFEOS_INFRA_STATE_DIR": str(state_dir),
            "ENV_FILE": str(env_file),
            "LIFEOS_WATCHDOG_CURL": str(bin_dir / "curl"),
            "HOME": str(tmp_path),
        }
        env.update(extra or {})
        return subprocess.run(
            ["bash", str(script)], env=env, capture_output=True, text=True, timeout=30
        )

    def run_args(script, args, extra=None):
        env = {"PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_ACTIONS": str(actions)}
        env.update(extra or {})
        return subprocess.run(["bash", str(script), *args], env=env, capture_output=True, text=True, timeout=30)

    b.timer = lambda extra=None: run_args(ENABLE_TIMER, ["example.timer"], extra)
    b.linger = lambda extra=None: run_args(LINGER, ["example-user"], extra)
    b.setup = lambda extra=None: run(SETUP, extra)
    b.watch = lambda extra=None: run(WATCHDOG, extra)
    b.log = lambda: actions.read_text().splitlines() if actions.exists() else []
    b.telegrams = lambda: [a for a in b.log() if "api.telegram.org" in a]
    b.table = lambda: json.loads(ts_state.read_text()) if ts_state.exists() else {}
    return b


PEBBLE_LINE = "https=8443 path=/webhooks/pebble target=http://127.0.0.1:9790/webhooks/pebble\n"


# ---- setup-tailscale.sh -----------------------------------------------------


def test_no_routes_file_applies_only_the_lifeos_route(box):
    r = box.setup()
    assert r.returncode == 0, r.stderr
    assert box.table() == {"443|/": "http://127.0.0.1:8000"}


def test_lifeos_port_is_honoured(box):
    box.setup({"LIFEOS_PORT": "8123"})
    assert box.table() == {"443|/": "http://127.0.0.1:8123"}


def test_declared_route_is_applied_and_visible_in_status(box):
    box.routes.write_text("# pebble ring\n" + PEBBLE_LINE)
    r = box.setup()
    assert r.returncode == 0, r.stderr
    assert box.table()["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"
    assert (
        "tailscale serve --bg --https=8443 --set-path /webhooks/pebble "
        "http://127.0.0.1:9790/webhooks/pebble"
    ) in box.log()


def test_never_resets_and_leaves_undeclared_routes_alone(box):
    box.ts_state.write_text(json.dumps({"9443|/other": "http://127.0.0.1:1"}))
    box.routes.write_text(PEBBLE_LINE)
    assert box.setup().returncode == 0
    assert box.setup().returncode == 0  # idempotent
    assert not any("reset" in a for a in box.log())
    table = box.table()
    assert table["9443|/other"] == "http://127.0.0.1:1"
    assert len(table) == 3


def test_missing_route_after_apply_fails_and_names_it(box):
    box.routes.write_text(PEBBLE_LINE)
    r = box.setup({"FAKE_TS_DROP_PORT": "8443"})
    assert r.returncode != 0
    assert "MISSING route: https=8443 path=/webhooks/pebble" in r.stderr


def test_funnel_without_opt_in_is_applied_privately_and_reported(box):
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + " funnel=on\n")
    r = box.setup()
    assert r.returncode != 0
    assert "LIFEOS_TAILSCALE_ALLOW_FUNNEL" in r.stderr
    assert not any(a.startswith("tailscale funnel") for a in box.log())
    assert box.table()["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"
    assert not any(k.startswith("funnel|") for k in box.table())


def test_funnel_on_the_lifeos_port_is_rejected_even_with_opt_in(box):
    box.routes.write_text("https=443 path=/x target=http://127.0.0.1:9000/x funnel=on\n")
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode != 0
    assert "never allowed on the LifeOS port" in r.stderr
    assert not any(a.startswith("tailscale funnel") for a in box.log())
    assert "443|/x" not in box.table()
    assert box.table()["443|/"] == "http://127.0.0.1:8000"


def test_watchdog_rejects_and_alerts_on_funnel_on_the_lifeos_port(box):
    box.routes.write_text("https=443 path=/x target=http://127.0.0.1:9000/x funnel=on\n")
    box.watch({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert not any(a.startswith("tailscale funnel") for a in box.log())
    assert "443|/x" not in box.table()
    assert len(box.telegrams()) == 1


def test_removing_the_opt_in_closes_existing_exposure(box):
    opt_in = {"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"}
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + " funnel=on\n")
    assert box.setup(opt_in).returncode == 0
    assert "funnel|8443" in box.table()
    box.watch()
    table = box.table()
    assert "funnel|8443" not in table
    assert table["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"
    assert len(box.telegrams()) >= 1


def test_funnel_allowed_with_opt_in(box):
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + " funnel=on\n")
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode == 0, r.stderr
    assert any(a.startswith("tailscale funnel --bg --https=8443") for a in box.log())


def test_invalid_route_line_fails_only_that_line(box):
    box.routes.write_text("https=abc path=nope target=ftp://x\n" + PEBBLE_LINE)
    r = box.setup()
    assert r.returncode != 0
    assert box.table() == {
        "443|/": "http://127.0.0.1:8000",
        "8443|/webhooks/pebble": "http://127.0.0.1:9790/webhooks/pebble",
    }


def test_crlf_routes_file_is_parsed(box):
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + "\r\n# note\r\n")
    r = box.setup()
    assert r.returncode == 0, r.stderr
    assert "8443|/webhooks/pebble" in box.table()


def test_watchdog_still_checks_valid_routes_when_a_line_is_malformed(box):
    box.routes.write_text("garbage\n" + PEBBLE_LINE)
    box.watch()
    assert "8443|/webhooks/pebble" in box.table()
    assert len(box.telegrams()) == 1


def test_setup_turns_off_funnel_on_a_declared_private_port(box):
    box.routes.write_text(PEBBLE_LINE)
    box.ts_state.write_text(json.dumps({"funnel|8443": True}))
    r = box.setup()
    assert r.returncode == 0, r.stderr
    table = box.table()
    assert "funnel|8443" not in table
    assert table["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"


# ---- infra-watchdog.sh ------------------------------------------------------


def test_watchdog_all_routes_present_does_nothing(box):
    box.routes.write_text(PEBBLE_LINE)
    box.setup()
    box.actions.write_text("")
    r = box.watch()
    assert r.returncode == 0
    assert not any("serve --bg" in a for a in box.log())
    assert box.telegrams() == []
    assert "all present" in (box.state_dir / "infra-watchdog.log").read_text()


def test_watchdog_reapplies_a_missing_route_without_alerting(box):
    box.routes.write_text(PEBBLE_LINE)
    box.setup()
    box.ts_state.write_text(json.dumps({"443|/": "http://127.0.0.1:8000"}))  # 8443 vanished
    r = box.watch()
    assert r.returncode == 0
    assert "8443|/webhooks/pebble" in box.table()
    assert box.telegrams() == []
    assert not any("reset" in a for a in box.log())
    assert "re-applied" in (box.state_dir / "infra-watchdog.log").read_text()


def test_watchdog_alerts_once_per_cooldown_when_route_cannot_be_restored(box):
    box.routes.write_text(PEBBLE_LINE)
    extra = {"FAKE_TS_DROP_PORT": "8443"}
    assert box.watch(extra).returncode == 0
    assert box.watch(extra).returncode == 0
    assert len(box.telegrams()) == 1
    assert "suppressed (cooldown)" in (box.state_dir / "infra-watchdog.log").read_text()


def test_watchdog_alerts_again_after_cooldown_expires(box):
    box.routes.write_text(PEBBLE_LINE)
    extra = {"FAKE_TS_DROP_PORT": "8443"}
    box.watch(extra)
    stamp = box.state_dir / "infra-watchdog-alert-routes.stamp"
    stamp.write_text("1")  # long ago
    box.watch(extra)
    assert len(box.telegrams()) == 2


def test_failed_telegram_send_does_not_start_a_cooldown(box):
    box.routes.write_text(PEBBLE_LINE)
    extra = {"FAKE_TS_DROP_PORT": "8443", "FAKE_TG_RC": "1"}
    box.watch(extra)
    box.watch(extra)
    assert len(box.telegrams()) == 2


def test_watchdog_pebble_unset_skips_the_check(box):
    box.watch({"FAKE_HEALTH_RC": "1"})
    assert not any("curl" in a and "api.telegram.org" not in a for a in box.log())


def test_watchdog_pebble_alerts_on_third_consecutive_failure(box):
    extra = {"LIFEOS_PEBBLE_HEALTH_URL": "http://127.0.0.1:9790/health", "FAKE_HEALTH_RC": "22"}
    box.watch(extra)
    box.watch(extra)
    assert box.telegrams() == []
    box.watch(extra)
    assert len(box.telegrams()) == 1


def test_watchdog_pebble_success_resets_the_strike_count(box):
    url = {"LIFEOS_PEBBLE_HEALTH_URL": "http://127.0.0.1:9790/health"}
    box.watch({**url, "FAKE_HEALTH_RC": "22"})
    box.watch({**url, "FAKE_HEALTH_RC": "22"})
    box.watch({**url, "FAKE_HEALTH_RC": "0"})
    box.watch({**url, "FAKE_HEALTH_RC": "22"})
    box.watch({**url, "FAKE_HEALTH_RC": "22"})
    assert box.telegrams() == []


def test_watchdog_ignores_obsidian_unless_expected(box):
    box.watch()
    assert not any(a.startswith(("pgrep", "systemd-run")) for a in box.log())


def test_watchdog_obsidian_running_does_not_relaunch(box):
    box.watch({"LIFEOS_EXPECT_OBSIDIAN_SYNC": "true", "FAKE_OBSIDIAN": "1"})
    assert not any(a.startswith("systemd-run") for a in box.log())
    assert box.telegrams() == []


def test_watchdog_obsidian_relaunches_then_alerts_if_still_absent(box):
    extra = {"LIFEOS_EXPECT_OBSIDIAN_SYNC": "true", "FAKE_OBSIDIAN": "0"}
    box.watch(extra)
    launches = [a for a in box.log() if a.startswith("systemd-run --user --collect --unit=obsidian-session-")]
    assert len(launches) == 1
    assert launches[0].endswith("snap run obsidian")
    assert box.telegrams() == []
    box.watch(extra)
    assert len(box.telegrams()) == 1
    assert len([a for a in box.log() if a.startswith("systemd-run")]) == 1


def test_watchdog_obsidian_recovery_clears_the_relaunch_marker(box):
    extra = {"LIFEOS_EXPECT_OBSIDIAN_SYNC": "true"}
    box.watch({**extra, "FAKE_OBSIDIAN": "0"})
    box.watch({**extra, "FAKE_OBSIDIAN": "1"})
    box.watch({**extra, "FAKE_OBSIDIAN": "0"})
    assert box.telegrams() == []
    assert len([a for a in box.log() if a.startswith("systemd-run")]) == 2


def test_watchdog_obsidian_launch_command_is_configurable(box):
    box.watch(
        {
            "LIFEOS_EXPECT_OBSIDIAN_SYNC": "true",
            "LIFEOS_OBSIDIAN_LAUNCH_CMD": "systemd-run --user custom-launch",
        }
    )
    assert "systemd-run --user custom-launch" in box.log()


def test_watchdog_turns_funnel_off_for_a_private_route_found_public(box):
    box.routes.write_text(PEBBLE_LINE)
    box.setup()
    state = box.table()
    state["funnel|8443"] = True
    box.ts_state.write_text(json.dumps(state))
    r = box.watch()
    assert r.returncode == 0
    assert "tailscale funnel --https=8443 off" in box.log()
    table = box.table()
    assert "funnel|8443" not in table
    # funnel off removed the handlers; they are re-applied privately.
    assert table["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"
    assert len(box.telegrams()) == 1


def test_watchdog_alerts_when_funnel_cannot_be_turned_off(box):
    box.routes.write_text(PEBBLE_LINE)
    box.setup()
    state = box.table()
    state["funnel|8443"] = True
    box.ts_state.write_text(json.dumps(state))
    box.watch({"FAKE_FUNNEL_STUCK": "1"})
    assert "FAILED" in (box.state_dir / "infra-watchdog.log").read_text()
    assert len(box.telegrams()) == 1


def test_watchdog_never_makes_a_private_port_public(box):
    box.routes.write_text(PEBBLE_LINE)
    box.watch()
    assert not any(a.startswith("tailscale funnel") and not a.endswith(" off") for a in box.log())
    assert not any(k.startswith("funnel|") for k in box.table())


def test_watchdog_reapplies_a_declared_public_route_found_private(box):
    opt_in = {"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"}
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + " funnel=on\n")
    assert box.setup(opt_in).returncode == 0
    state = box.table()
    del state["funnel|8443"]
    box.ts_state.write_text(json.dumps(state))
    box.watch(opt_in)
    assert "funnel|8443" in box.table()


def test_user_timer_enabled_when_reload_and_enable_succeed(box):
    r = box.timer()
    assert r.returncode == 0, r.stderr
    assert "systemctl --user enable --now example.timer" in box.log()


def test_user_timer_daemon_reload_failure_is_propagated(box):
    r = box.timer({"FAKE_DAEMON_RELOAD_RC": "1"})
    assert r.returncode != 0
    assert "NOT enabled" in r.stderr
    assert not any("enable" in a for a in box.log())


def test_user_timer_enable_failure_is_propagated(box):
    assert box.timer({"FAKE_ENABLE_RC": "1"}).returncode != 0


def test_declared_public_route_without_opt_in_stays_private_in_the_watchdog(box):
    box.routes.write_text(PEBBLE_LINE.rstrip("\n") + " funnel=on\n")
    box.watch()
    assert not any(k.startswith("funnel|") for k in box.table())
    assert not any(a.startswith("tailscale funnel") for a in box.log())


def test_telegram_send_has_connect_and_total_timeouts(box):
    box.routes.write_text(PEBBLE_LINE)
    box.watch({"FAKE_TS_DROP_PORT": "8443"})
    (call,) = box.telegrams()
    assert "--connect-timeout 5" in call
    assert "--max-time 15" in call


def test_service_unit_has_a_start_timeout():
    assert "TimeoutStartSec=120" in SERVICE.read_text()


def test_linger_enabled_and_verified(box):
    r = box.linger()
    assert r.returncode == 0, r.stderr
    assert "loginctl enable-linger example-user" in box.log()
    assert "loginctl show-user example-user -p Linger" in box.log()


def test_linger_failure_is_loud_and_nonzero(box):
    r = box.linger({"FAKE_LINGER": "no"})
    assert r.returncode != 0
    assert "WARNING" in r.stderr


def test_watchdog_exits_zero_on_invalid_routes_file(box):
    box.routes.write_text("garbage\n")
    r = box.watch()
    assert r.returncode == 0
    assert len(box.telegrams()) == 1


def test_make_private_failure_is_reported_when_routes_cannot_be_restored(box):
    box.routes.write_text(PEBBLE_LINE)
    box.ts_state.write_text(
        json.dumps(
            {
                "8443|/webhooks/pebble": "http://127.0.0.1:9790/webhooks/pebble",
                "funnel|8443": True,
            }
        )
    )
    r = box.setup({"FAKE_TS_DROP_PORT": "8443"})
    assert r.returncode != 0
    assert "NOT PRIVATE: https=8443" in r.stderr
    box.ts_state.write_text(json.dumps({"funnel|8443": True}))
    box.watch({"FAKE_TS_DROP_PORT": "8443"})
    assert "make-private FAILED" in (box.state_dir / "infra-watchdog.log").read_text()


@pytest.mark.parametrize("port", ["0673", "0443", "0x1BB", "+443", "70000", "0", " 443"])
def test_non_canonical_ports_are_malformed_lines(box, port):
    box.routes.write_text(f"https={port} path=/x target=http://127.0.0.1:9000/x funnel=on\n")
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode != 0
    assert "must be a decimal number" in r.stderr or "expected key=value" in r.stderr
    assert not any(a.startswith("tailscale funnel") for a in box.log())
    assert not any(k.startswith("funnel|") for k in box.table())
    assert set(box.table()) == {"443|/"}


def test_trailing_space_after_the_port_is_the_canonical_port(box):
    box.routes.write_text("https=443  path=/x target=http://127.0.0.1:9000/x funnel=on \n")
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode != 0
    assert "never allowed on the LifeOS port" in r.stderr
    assert set(box.table()) == {"443|/"}


MCP_PUBLIC_LINES = (
    "https=10000 path=/mcp target=http://127.0.0.1:8765/mcp funnel=on\n"
    "https=10000 path=/oauth/token target=http://127.0.0.1:8765/oauth/token funnel=on\n"
)


def test_port_mixing_public_and_private_routes_applies_none_of_them(box):
    box.routes.write_text(
        MCP_PUBLIC_LINES
        + "https=10000 path=/private target=http://127.0.0.1:9000/private\n"
        + PEBBLE_LINE
    )
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode != 0
    assert "https=10000 mixes funnel=on and private routes" in r.stderr
    table = box.table()
    assert not any(k.startswith("10000|") or k == "funnel|10000" for k in table)
    assert table["8443|/webhooks/pebble"] == "http://127.0.0.1:9790/webhooks/pebble"


def test_watchdog_never_publishes_a_port_with_mixed_routes(box):
    box.routes.write_text(MCP_PUBLIC_LINES + "https=10000 path=/private target=http://127.0.0.1:9000/private\n")
    box.watch({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert not any(a.startswith("tailscale funnel") for a in box.log())
    assert "funnel|10000" not in box.table()


def test_public_port_with_only_funnel_routes_is_published(box):
    box.routes.write_text(MCP_PUBLIC_LINES + PEBBLE_LINE)
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode == 0, r.stderr
    table = box.table()
    assert table["funnel|10000"] is True
    assert table["10000|/mcp"] == "http://127.0.0.1:8765/mcp"
    assert "funnel|8443" not in table


def test_setup_closes_an_already_public_port_once_it_is_declared_mixed(box):
    box.ts_state.write_text(json.dumps({
        "10000|/mcp": "http://127.0.0.1:8765/mcp",
        "10000|/private": "http://127.0.0.1:9000/private",
        "funnel|10000": True,
    }))
    box.routes.write_text(MCP_PUBLIC_LINES + "https=10000 path=/private target=http://127.0.0.1:9000/private\n")
    r = box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert r.returncode != 0
    assert "tailscale funnel --https=10000 off" in box.log()
    assert "funnel|10000" not in box.table()


def test_watchdog_closes_an_already_public_port_once_it_is_declared_mixed(box):
    box.ts_state.write_text(json.dumps({"10000|/mcp": "http://127.0.0.1:8765/mcp", "funnel|10000": True}))
    box.routes.write_text(MCP_PUBLIC_LINES + "https=10000 path=/private target=http://127.0.0.1:9000/private\n")
    box.watch({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"})
    assert "funnel|10000" not in box.table()
    assert len(box.telegrams()) >= 1


def test_withdrawing_the_opt_in_closes_the_public_mcp_port(box):
    box.routes.write_text(MCP_PUBLIC_LINES)
    assert box.setup({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "true"}).returncode == 0
    assert "funnel|10000" in box.table()
    box.watch({"LIFEOS_TAILSCALE_ALLOW_FUNNEL": "false"})
    table = box.table()
    assert "funnel|10000" not in table
    assert table["10000|/mcp"] == "http://127.0.0.1:8765/mcp"
