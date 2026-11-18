// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "GalaxySidecar",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "sidecar-host", targets: ["SidecarHost"]),
        .executable(name: "sidecar-probe", targets: ["SidecarProbe"]),
    ],
    targets: [
        .executableTarget(name: "SidecarHost", dependencies: ["VirtualDisplay"]),
        .target(name: "VirtualDisplay", cSettings: [.unsafeFlags(["-fobjc-arc"])]),
        .executableTarget(name: "SidecarProbe"),
    ]
)
