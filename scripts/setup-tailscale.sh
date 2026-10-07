#!/usr/bin/env bash
# Apply LifeOS's tailnet HTTPS front (port 443) plus every route declared in
# config/tailscale-routes.local, then verify each one is live. Required for
# /chat voice (getUserMedia needs a secure context). whisper-relay stays on
# localhost:9788; LifeOS reverse-proxies /api/voice/* (ADR-016).
#
# Routes the file does not declare are left untouched: this script never resets
# the serve config. Exits non-zero (naming each route) if a declared route is
# missing after the apply, so the boot unit fails visibly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Port/URL come from the environment (systemd EnvironmentFile or caller export).
# Do not source .env here — paths may contain spaces.
# shellcheck source=lib/tailscale-routes.sh
source "$ROOT/scripts/lib/tailscale-routes.sh"

ROUTES_FILE="${LIFEOS_TAILSCALE_ROUTES_FILE:-$ROOT/config/tailscale-routes.local}"

# A malformed line drops only that line: the valid routes (always including
# LifeOS's own) are still applied, then the run fails.
invalid=0
ts_load_routes "$ROUTES_FILE" || invalid=1

for route in "${ROUTES[@]}"; do
  ts_apply_route "$route"
done

missing=0
for route in "${ROUTES[@]}"; do
  IFS='|' read -r port path target _ <<< "$route"
  if ! ts_route_present "$port" "$path" "$target"; then
    echo "MISSING route: https=${port} path=${path} target=${target}" >&2
    missing=1
  fi
done

# Exposure must match the declaration: a route is public only when declared
# funnel=on (which the loader accepts only with the opt-in).
for port in $(ts_declared_ports); do
  if ts_wants_public "$port"; then
    if ! ts_port_public "$port"; then
      echo "MISSING funnel exposure on https=${port}" >&2
      missing=1
    fi
  elif ts_port_public "$port"; then
    echo "Port https=${port} is public but declared private; turning funnel off." >&2
    if ! ts_make_private "$port"; then
      echo "NOT PRIVATE: https=${port} (route missing or still public)" >&2
      missing=1
    fi
  fi
done

if [[ $invalid -eq 1 ]]; then
  echo "Routes file has invalid lines (skipped); see messages above." >&2
  missing=1
fi

echo "LifeOS tailnet URLs:"
if [[ -n "${TAILNET_HTTPS_URL:-}" ]]; then
  echo "  HTTPS /chat (voice): ${TAILNET_HTTPS_URL}/chat"
else
  echo "  Set TAILNET_HTTPS_URL in .env for a stable bookmark hint."
fi
tailscale serve status

exit "$missing"
