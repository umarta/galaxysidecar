#!/bin/sh
set -eu

label=dev.galaxysidecar.host
domain="gui/$(id -u)"
plist="$HOME/Library/LaunchAgents/$label.plist"
case "${1:-}" in
    status)
        exec launchctl print "$domain/$label"
        ;;
    start)
        if [ ! -r "$plist" ]; then
            echo 'Install the login host first.' >&2
            exit 1
        fi
        if launchctl print "$domain/$label" >/dev/null 2>&1; then
            exec launchctl kickstart "$domain/$label"
        fi
        exec launchctl bootstrap "$domain" "$plist"
        ;;
    remove)
        if launchctl print "$domain/$label" >/dev/null 2>&1; then
            launchctl bootout "$domain/$label"
        fi
        rm -f "$plist"
        ;;
    install)
        if [ "$#" -lt 3 ] || [ "$#" -gt 4 ] || { [ "$#" -eq 4 ] && [ "$4" != --virtual-display ]; }; then
            echo 'Usage: login-host.sh install MAC_TAILNET_IPV4 TABLET_TAILNET_IPV4 [--virtual-display]' >&2
            exit 2
        fi
        root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
        host="$root/macos/.build/debug/sidecar-host"
        if [ ! -x "$host" ]; then
            echo 'Build the Mac host with swift build in macos/ first.' >&2
            exit 1
        fi
        shift
        logs="$HOME/Library/Logs/GalaxySidecar"
        mkdir -p "$HOME/Library/LaunchAgents" "$logs"
        prepared=$(mktemp "$plist.XXXXXXXX")
        trap 'rm -f "$prepared"' EXIT
        python3 - "$label" "$host" "$logs" "$prepared" "$@" <<'PY'
import ipaddress
import os
import plistlib
import sys

label, host, logs, output, local, tablet, *options = sys.argv[1:]
tailnet = ipaddress.ip_network("100.64.0.0/10")
if any(ipaddress.ip_address(addr) not in tailnet for addr in (local, tablet)):
    raise SystemExit("Both addresses must be Tailscale IPv4 addresses.")
with open(output, "wb") as fd:
    plistlib.dump({
        "Label": label,
        "ProgramArguments": [host, "--remote", local, tablet, *options],
        "RunAtLoad": True,
        "KeepAlive": {"SuccessfulExit": False},
        "ThrottleInterval": 10,
        "LimitLoadToSessionType": "Aqua",
        "EnvironmentVariables": {"GALAXYSIDECAR_BACKGROUND": "1", "NSUnbufferedIO": "YES"},
        "StandardOutPath": os.path.join(logs, "host.log"),
        "StandardErrorPath": os.path.join(logs, "host-error.log"),
    }, fd)
PY
        plutil -lint "$prepared" >/dev/null
        if launchctl print "$domain/$label" >/dev/null 2>&1; then
            launchctl bootout "$domain/$label"
        fi
        mv "$prepared" "$plist"
        launchctl bootstrap "$domain" "$plist"
        printf 'GalaxySidecar is configured to start at login. Logs: %s\n' "$logs"
        ;;
    *)
        echo 'Usage: login-host.sh {install MAC_TAILNET_IPV4 TABLET_TAILNET_IPV4 [--virtual-display]|start|status|remove}' >&2
        exit 2
        ;;
esac
