import CoreGraphics
import Darwin
import Foundation
import ScreenCaptureKit

print("macOS \(ProcessInfo.processInfo.operatingSystemVersionString)")
for name in ["CGVirtualDisplay", "CGVirtualDisplayDescriptor", "CGVirtualDisplayMode", "CGVirtualDisplaySettings"] {
    print("\(name): \(NSClassFromString(name) == nil ? "not found" : "present")")
}
print("Class presence is informational. This probe does not create a virtual display.")

guard CGPreflightScreenCaptureAccess() || CGRequestScreenCaptureAccess() else {
    print("Grant Screen & System Audio Recording permission, then relaunch the probe.")
    exit(EXIT_FAILURE)
}
do {
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
    print("Displays: \(content.displays.count)")
    for display in content.displays {
        let main = display.displayID == CGMainDisplayID() ? " (main)" : ""
        print("  \(display.displayID): \(display.width) × \(display.height)\(main)")
    }
    if content.displays.isEmpty { exit(EXIT_FAILURE) }
} catch {
    print("Display discovery failed: \(error.localizedDescription)")
    exit(EXIT_FAILURE)
}
