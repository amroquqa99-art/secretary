#!/usr/bin/env bash
# Enable systemd lingering for a user and verify it, so that user's timers run
# after a reboot with no login session. Usage: ensure-linger.sh <user>
# Exits non-zero (with a loud warning) if lingering cannot be confirmed.
set -uo pipefail

user="${1:?usage: ensure-linger.sh <user>}"

loginctl enable-linger "$user" 2>&1 || true
if [[ "$(loginctl show-user "$user" -p Linger 2>/dev/null)" == "Linger=yes" ]]; then
    echo "  linger: enabled for $user"
    exit 0
fi
echo "WARNING: could not enable lingering for $user. User timers (lifeos-infra-watchdog.timer) will NOT run after a headless reboot." >&2
echo "         Fix: sudo loginctl enable-linger $user" >&2
exit 1
