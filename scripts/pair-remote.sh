#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo 'Usage: pair-remote.sh MAC_TAILNET_IPV4' >&2
    exit 2
fi
case "$1" in
    ''|*[!0-9.]*) exit 2 ;;
esac
token="$HOME/Library/Application Support/GalaxySidecar/remote-input-token"
if [ ! -r "$token" ]; then
    echo 'Start sidecar-host --remote before pairing.' >&2
    exit 1
fi

{ printf '%s\n' "$1"; cat "$token"; } |
    adb -d shell -T 'run-as dev.galaxysidecar sh -c "umask 077; mkdir -p files && cat > files/remote-pairing.tmp && mv files/remote-pairing.tmp files/remote-pairing"'
echo 'Remote profile paired. Open GalaxySidecar and tap Remote desktop.'
