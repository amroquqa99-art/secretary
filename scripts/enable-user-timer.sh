#!/usr/bin/env bash
# Reload the calling user's systemd manager and enable+start a user timer.
# Usage: enable-user-timer.sh <timer-unit>   (run as that user, with
# XDG_RUNTIME_DIR set). Exits non-zero, saying why, if any step fails.
set -uo pipefail

timer="${1:?usage: enable-user-timer.sh <timer-unit>}"

if ! systemctl --user daemon-reload; then
    echo "WARNING: user daemon-reload failed (no user session?); $timer was NOT enabled." >&2
    echo "         Run 'systemctl --user enable --now $timer' from a login session." >&2
    exit 1
fi
if ! systemctl --user enable --now "$timer"; then
    echo "WARNING: enabling $timer failed." >&2
    exit 1
fi
echo "  $timer (user): $(systemctl --user is-active "$timer")"
