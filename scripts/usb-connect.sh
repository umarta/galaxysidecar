#!/bin/sh
set -eu

if ! command -v adb >/dev/null 2>&1; then
    echo 'adb is missing. Add Android SDK platform-tools to PATH.' >&2
    exit 1
fi

refresh_pairing() {
    token_file="${TMPDIR:-/tmp}/galaxysidecar-$(id -u)/input-token"
    if [ -r "$token_file" ]; then
        adb -d shell -T 'run-as dev.galaxysidecar sh -c "umask 077; mkdir -p files && cat > files/usb-input-token"' < "$token_file"
    fi
}

case "${1:-}" in
    "")
        adb -d reverse tcp:5555 tcp:5555
        refresh_pairing
        echo 'ADB reverse is active. Connect to 127.0.0.1 in GalaxySidecar.'
        ;;
    --watch)
        echo 'Waiting for an authorized USB device. Press Ctrl+C to stop.'
        prompted=false
        while true; do
            if [ "$(adb -d get-state 2>/dev/null || true)" != device ]; then
                prompted=false
            elif [ "$prompted" = false ]; then
                if adb -d reverse tcp:5555 tcp:5555 >/dev/null && refresh_pairing &&
                    adb -d shell am start -W -n dev.galaxysidecar/.MainActivity \
                        --ez dev.galaxysidecar.USB_ATTACHED true; then
                    prompted=true
                fi
            fi
            sleep 1
        done
        ;;
    *)
        echo 'Usage: usb-connect.sh [--watch]' >&2
        exit 2
        ;;
esac
