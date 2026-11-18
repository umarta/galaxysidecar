# GalaxySidecar

Use a Galaxy Tab as an extended display for your Mac, mirror your desktop over USB or Wi-Fi, or control your Mac remotely over Tailscale. GalaxySidecar pairs a native Swift macOS host with a Kotlin Android viewer.

**Status:** Early open-source prototype, tested on a Mac mini M4 running macOS 27 and a Galaxy Tab S10+. The host builds for macOS 14 or later. Extended display support uses private macOS APIs and may vary by version.

| Mode | Transport | What you can do |
| --- | --- | --- |
| **Extend Mac display** | Tailscale | Create a separate Mac display with the Galaxy Tab S10+ aspect ratio. |
| **Connect remotely** | Tailscale | View and control the desktop with touch, S Pen, keyboard, and trackpad mode. |
| **Connect over Wi-Fi** | Trusted local network | Mirror the Mac desktop. |
| **Use USB cable** | ADB reverse | Mirror the desktop, with optional paired input. Requires USB debugging. |

## Getting started

### Requirements

- **Mac:** macOS 14 or later, Xcode with Swift 6, and an active desktop session.
- **Galaxy Tab:** Android 8 or later. To build the viewer, use Android Studio or Gradle 8.14, Android SDK 36, and JDK 17 or another compatible JDK. This repository does not bundle a Gradle wrapper.
- **Remote mode:** Both devices on the same [Tailscale network](https://tailscale.com/docs/install). An authorized USB debugging connection is required once to pair the debug APK.

### Build and install the Android viewer

From the repository root:

```sh
cd android
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open `android/` in Android Studio and run the `app` configuration on the tablet.

### Connect remotely or extend the desktop

1. Start the host with both devices’ **Tailscale IPv4** addresses.

   ```sh
   cd macos
   swift run sidecar-host --remote MAC_TAILNET_IPV4 TABLET_TAILNET_IPV4
   ```

2. If prompted, grant **Screen & System Audio Recording** in macOS **System Settings → Privacy & Security**. Grant **Accessibility** for keyboard and pointer control. Relaunch the host after changing permissions.
3. Connect the tablet via USB and authorize ADB. From the repository root, pair the viewer.

   ```sh
   ./scripts/pair-remote.sh MAC_TAILNET_IPV4
   ```

4. Open GalaxySidecar and choose **Connect remotely** to mirror the desktop or **Extend Mac display** for a separate screen. Subsequent sessions can run without USB. Arrange the extended display in macOS **System Settings → Displays** while the session is active.

## Local connections

### Wi-Fi

On a trusted LAN, start a view-only host bound to the Mac’s local IPv4 address and restricted to the tablet’s source IPv4 address.

```sh
cd macos
swift run sidecar-host --lan-view MAC_LAN_IPV4 TABLET_LAN_IPV4
```

Enter the Mac’s LAN address in GalaxySidecar and tap **Connect over Wi-Fi**. Local Wi-Fi video is unencrypted. Keep TCP 5555 off the public internet.

### USB-C

Start `swift run sidecar-host --loopback` from `macos/`. Use `--loopback --input` if you want mouse, keyboard, or pen input and have granted Accessibility.

With a USB data cable connected and debugging authorized, run `./scripts/usb-connect.sh` from the repository root, then tap **Use USB cable**. Add `--watch` to open the tablet’s monitor prompt automatically when ADB detects the connection. Rerun the script when restarting an input-enabled USB host to refresh the USB pairing token.

## Using the viewer

- Open **Controls → Input tools** for Mac shortcuts, trackpad mode, and manual clipboard transfers of up to 64 KiB of UTF-8 text.
- Use a physical keyboard, or choose **Controls → Keyboard** to send text from the tablet’s on-screen keyboard.
- In trackpad mode, use two fingers to scroll or right-click, and hold then drag to select. S Pen retains direct positioning. Pen pressure depends on the receiving Mac app.
- The viewer retries transient disconnects and resumes after returning to the app. If the Mac is locked, GalaxySidecar waits for unlock and checks again every five seconds while the viewer is open.

## Start the host at login

Build the host with `swift build` in `macos/`, then run the following from the repository root:

```sh
./scripts/login-host.sh install MAC_TAILNET_IPV4 TABLET_TAILNET_IPV4
```

The script installs a per-user LaunchAgent. macOS may require separate Screen Recording and Accessibility approval for the background executable. After granting permissions, stop any manually running host on port 5556 and run `./scripts/login-host.sh start`. Use `status` to inspect the agent and `remove` to uninstall it. Logs are under `~/Library/Logs/GalaxySidecar/`.

## Architecture and limitations

ScreenCaptureKit captures the Mac display. VideoToolbox encodes H.264 with an adaptive bitrate, and Android MediaCodec decodes to a surface. TCP carries video and input. Remote mode binds to the Mac’s Tailscale IPv4 address and checks the tablet’s source address and pairing token before streaming. Local Wi-Fi mode is view-only. The host can fall back to JPEG when hardware encoding is unavailable, or you can force it with `--jpeg`. See the [wire protocol](docs/protocol.md) for details.

GalaxySidecar streams an unlocked macOS desktop session. Boot, FileVault, and login-screen video, audio, and plug-and-play USB display mode are outside the current scope. Extended displays exist only during an active session. Actual frame rate and latency depend on the devices and network.

## Contributing

Issues and pull requests are welcome. For display, decoding, or connection reports, include the Mac model, macOS version, Android device and version, connection mode, and steps to reproduce. Remove private addresses and pairing information from logs before posting.

## License

[MIT](LICENSE)
