#!/usr/bin/env bash
# Shared helpers for declared tailnet routes. Sourced by setup-tailscale.sh and
# infra-watchdog.sh; defines functions only.
#
# Routes come from LifeOS's own front (https=443 path=/ -> the local API) plus
# every line of the operator-local routes file (config/tailscale-routes.local,
# git-ignored; see config/tailscale-routes.example for the format).

# Populates the global ROUTES array with "port|path|target|funnel" entries, and
# REJECTED_PORTS with ports whose routes were all refused for mixing public and
# private routes. Prints one message per problem to stderr and returns 1 if the
# file is invalid.
LIFEOS_ROUTE_PORT=443

ts_load_routes() {
    local routes_file="$1" port="${LIFEOS_PORT:-8000}"
    ROUTES=("443|/|http://127.0.0.1:${port}|off")
    REJECTED_PORTS=()
    [[ -f "$routes_file" ]] || return 0

    local line lineno=0 rc=0 token key value bad
    local r_port r_path r_target r_funnel
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$((lineno + 1))
        line="${line%$'\r'}"
        line="${line%%#*}"
        [[ -n "${line//[[:space:]]/}" ]] || continue
        r_port="" r_path="" r_target="" r_funnel="off" bad=0
        for token in $line; do
            if [[ "$token" != *=* ]]; then
                echo "$routes_file:$lineno: expected key=value, got '$token'" >&2
                bad=1
                continue
            fi
            key="${token%%=*}"
            value="${token#*=}"
            case "$key" in
                https) r_port="$value" ;;
                path) r_path="$value" ;;
                target) r_target="$value" ;;
                funnel) r_funnel="$value" ;;
                *) echo "$routes_file:$lineno: unknown field '$key'" >&2; bad=1 ;;
            esac
        done
        # Canonical decimal only: Tailscale parses a leading 0 as octal and 0x as
        # hex, so "0673" would silently mean port 443.
        if ! [[ "$r_port" =~ ^[1-9][0-9]{0,4}$ ]] || (( r_port > 65535 )); then
            echo "$routes_file:$lineno: https=<port> must be a decimal number from 1 to 65535 without leading zeros" >&2
            bad=1
        fi
        [[ "$r_path" == /* ]] || { echo "$routes_file:$lineno: path=<mount path> must start with /" >&2; bad=1; }
        [[ "$r_target" =~ ^https?://[^[:space:]]+$ ]] || { echo "$routes_file:$lineno: target=<url> must be an http(s) URL" >&2; bad=1; }
        case "$r_funnel" in
            off) ;;
            on)
                if [[ "$r_port" == "$LIFEOS_ROUTE_PORT" ]]; then
                    # Funnel is port-wide: it would publish the LifeOS API itself.
                    echo "$routes_file:$lineno: funnel=on is never allowed on the LifeOS port (https=$LIFEOS_ROUTE_PORT)" >&2
                    bad=1
                elif [[ "${LIFEOS_TAILSCALE_ALLOW_FUNNEL:-false}" != "true" ]]; then
                    # Without the opt-in the line is a private declaration, and
                    # still reported so the ignored setting is not silent.
                    echo "$routes_file:$lineno: funnel=on ignored (LIFEOS_TAILSCALE_ALLOW_FUNNEL is not true); route applied privately" >&2
                    r_funnel="off"
                    rc=1
                fi
                ;;
            *) echo "$routes_file:$lineno: funnel must be on or off" >&2; bad=1 ;;
        esac
        if [[ $bad -eq 1 ]]; then
            rc=1
        else
            ROUTES+=("${r_port}|${r_path}|${r_target}|${r_funnel}")
        fi
    done < "$routes_file"

    # Funnel is port-wide, so one funnel=on line publishes every route on its
    # port. A port declaring both is ambiguous: none of its routes are kept.
    local port_on=" " port_off=" " route mixed kept=()
    for route in "${ROUTES[@]}"; do
        IFS='|' read -r r_port _ _ r_funnel <<< "$route"
        if [[ "$r_funnel" == "on" ]]; then port_on+="$r_port "; else port_off+="$r_port "; fi
    done
    for route in "${ROUTES[@]}"; do
        IFS='|' read -r r_port _ <<< "$route"
        mixed=0
        [[ "$port_on" == *" $r_port "* && "$port_off" == *" $r_port "* ]] && mixed=1
        if [[ $mixed -eq 1 ]]; then
            if [[ " ${REJECTED_PORTS[*]} " != *" $r_port "* ]]; then
                echo "$routes_file: https=$r_port mixes funnel=on and private routes; Funnel is port-wide, so no route on that port is applied and the port is kept private" >&2
                REJECTED_PORTS+=("$r_port")
            fi
            rc=1
        else
            kept+=("$route")
        fi
    done
    ROUTES=("${kept[@]}")
    return $rc
}

# Succeeds when `tailscale serve status --json` shows a handler for the route
# whose proxy target matches. The tailnet hostname is not needed: any
# "<host>:<port>" web entry on that port counts.
ts_route_present() {
    local port="$1" path="$2" target="$3"
    tailscale serve status --json 2>/dev/null | python3 -c '
import json, sys
port, path, target = sys.argv[1:4]
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
for hostport, web in (data.get("Web") or {}).items():
    if hostport.rsplit(":", 1)[-1] != port:
        continue
    handler = (web.get("Handlers") or {}).get(path) or {}
    if (handler.get("Proxy") or "").rstrip("/") == target.rstrip("/"):
        sys.exit(0)
sys.exit(1)
' "$port" "$path" "$target"
}

# Applies one "port|path|target|funnel" entry. Never resets other routes.
ts_apply_route() {
    local port path target funnel
    IFS='|' read -r port path target funnel <<< "$1"
    if [[ "$funnel" == "on" ]]; then
        tailscale funnel --bg --https="$port" --set-path "$path" "$target"
    else
        tailscale serve --bg --https="$port" --set-path "$path" "$target"
    fi
}

# Succeeds when the tailnet port is publicly exposed (Funnel) on any host entry.
ts_port_public() {
    local port="$1"
    tailscale serve status --json 2>/dev/null | python3 -c '
import json, sys
port = sys.argv[1]
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
for hostport, on in (data.get("AllowFunnel") or {}).items():
    if on and hostport.rsplit(":", 1)[-1] == port:
        sys.exit(0)
sys.exit(1)
' "$port"
}

# Succeeds when any declared route on the port has funnel=on.
ts_wants_public() {
    local port="$1" route r_port funnel
    for route in "${ROUTES[@]}"; do
        IFS='|' read -r r_port _ _ funnel <<< "$route"
        [[ "$r_port" == "$port" && "$funnel" == "on" ]] && return 0
    done
    return 1
}

# Declared ports, one per line, without duplicates. A rejected (mixed) port is
# included: it declares no route, so exposure checks keep it private.
ts_declared_ports() {
    local route r_port
    {
        for route in "${ROUTES[@]}"; do
            IFS='|' read -r r_port _ <<< "$route"
            echo "$r_port"
        done
        for r_port in "${REJECTED_PORTS[@]}"; do
            echo "$r_port"
        done
    } | sort -un
}

# Closes public exposure on a port and restores its declared routes privately.
# `tailscale funnel --https=<port> off` removes the port's web handlers as well
# as the exposure, so every declared route on the port is re-applied afterwards.
# Succeeds only when each route is present again and the port is not public.
ts_make_private() {
    local port="$1" route r_port r_path r_target ok=0
    tailscale funnel --https="$port" off > /dev/null 2>&1
    for route in "${ROUTES[@]}"; do
        IFS='|' read -r r_port r_path r_target _ <<< "$route"
        [[ "$r_port" == "$port" ]] || continue
        tailscale serve --bg --https="$port" --set-path "$r_path" "$r_target" > /dev/null 2>&1
        ts_route_present "$r_port" "$r_path" "$r_target" || ok=1
    done
    ts_port_public "$port" && ok=1
    return $ok
}
