#!/usr/bin/env bash
# The public MCP node: a Tailscale node in its own container, with its own
# tailnet hostname, whose port 443 is published with Funnel to the MCP HTTP
# transport's public unix-socket listener. Connector platforms only connect out
# on port 443 and Funnel exposure is port-wide, so the public listener gets a
# node of its own and LifeOS's own 443 stays tailnet-only. The container's
# network namespace keeps the node from forwarding tailnet traffic to any host
# loopback service; its only way into LifeOS is the mounted socket.
#
# Usage: mcp-funnel-node.sh up|apply|check|login
#   up     create or start the container, then apply
#   apply  wait for the node to be logged in, then publish exactly the socket
#   check  exit 0 only when the node is Running and publishes exactly the socket
#   login  print a login URL for a logged-out node
# Exit 3 means the node needs a login.
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

# A setting from the environment, else from .env, else the default.
setting() {
    local key="$1" default="$2" value="${!1:-}"
    if [[ -z "$value" && -f "$ENV_FILE" ]]; then
        value=$(grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2-)
    fi
    echo "${value:-$default}"
}

CONTAINER=$(setting LIFEOS_MCP_FUNNEL_CONTAINER lifeos-mcp-funnel)
IMAGE=$(setting LIFEOS_MCP_FUNNEL_IMAGE tailscale/tailscale:v1.102.4)
STATE_DIR=$(setting LIFEOS_MCP_FUNNEL_STATE_DIR "$HOME/.local/share/lifeos-mcp-ts")
NODE_HOSTNAME=$(setting LIFEOS_MCP_FUNNEL_HOSTNAME lifeos-mcp)
UDS=$(setting LIFEOS_MCP_HTTP_UDS "")
WAIT_SECONDS=$(setting LIFEOS_MCP_FUNNEL_WAIT_SECONDS 60)
TARGET="unix:/sock/$(basename "${UDS:-mcp.sock}")"

ts() { docker exec "$CONTAINER" tailscale "$@"; }

container_state() {
    docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo missing
}

backend_state() {
    ts status --json 2>/dev/null | python3 -c '
import json, sys
try:
    print(json.load(sys.stdin).get("BackendState") or "")
except ValueError:
    print("")
'
}

# Succeeds when 443 is the node's only served port, "/" its only handler,
# proxying to the socket, and 443 is Funnel-exposed.
published_exactly() {
    ts serve status --json 2>/dev/null | python3 -c '
import json, sys
target = sys.argv[1]
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
web = data.get("Web") or {}
if len(web) != 1:
    sys.exit(1)
hostport, entry = next(iter(web.items()))
if hostport.rsplit(":", 1)[-1] != "443":
    sys.exit(1)
if (entry.get("Handlers") or {}) != {"/": {"Proxy": target}}:
    sys.exit(1)
if set((data.get("TCP") or {})) != {"443"}:
    sys.exit(1)
funnel = {hp: on for hp, on in (data.get("AllowFunnel") or {}).items() if on}
if set(funnel) != {hostport}:
    sys.exit(1)
if data.get("Services") or data.get("Foreground"):
    sys.exit(1)
' "$TARGET"
}

apply() {
    local state="" waited=0
    while (( waited <= WAIT_SECONDS )); do
        state=$(backend_state)
        [[ "$state" == "Running" || "$state" == "NeedsLogin" ]] && break
        sleep 2
        waited=$((waited + 2))
    done
    if [[ "$state" == "NeedsLogin" ]]; then
        echo "MCP Funnel node is logged out; run: $0 login" >&2
        return 3
    fi
    if [[ "$state" != "Running" ]]; then
        echo "MCP Funnel node did not reach Running (state: ${state:-unreachable})" >&2
        return 1
    fi
    published_exactly && return 0
    ts serve reset > /dev/null 2>&1
    ts funnel --bg --https=443 "$TARGET" > /dev/null 2>&1
    if ! published_exactly; then
        echo "MCP Funnel node: the socket is not published exactly after apply" >&2
        return 1
    fi
}

up() {
    if [[ -z "$UDS" ]]; then
        echo "LIFEOS_MCP_HTTP_UDS is not set; the public node has nothing to publish" >&2
        return 1
    fi
    case "$(container_state)" in
        true) ;;
        false) docker start "$CONTAINER" > /dev/null || return 1 ;;
        *)
            mkdir -p "$STATE_DIR" "$(dirname "$UDS")"
            chmod 700 "$STATE_DIR" "$(dirname "$UDS")"
            docker run -d --name "$CONTAINER" --restart unless-stopped --network bridge \
                -e TS_USERSPACE=true -e TS_STATE_DIR=/var/lib/tailscale \
                -e TS_HOSTNAME="$NODE_HOSTNAME" -e TS_ACCEPT_DNS=false \
                -v "$STATE_DIR:/var/lib/tailscale" -v "$(dirname "$UDS"):/sock:ro" \
                "$IMAGE" > /dev/null || return 1
            ;;
    esac
    apply
}

case "${1:-}" in
    up) up ;;
    apply) apply ;;
    check)
        [[ "$(container_state)" == "true" ]] || exit 1
        case "$(backend_state)" in
            Running) published_exactly ;;
            NeedsLogin) exit 3 ;;
            *) exit 1 ;;
        esac
        ;;
    login) timeout 20 docker exec "$CONTAINER" tailscale login --hostname="$NODE_HOSTNAME" 2>&1 | grep -oE 'https://login\.tailscale\.com/\S+' | head -1 ;;
    *) echo "usage: $0 up|apply|check|login" >&2; exit 2 ;;
esac
