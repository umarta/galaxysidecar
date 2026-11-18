import ApplicationServices
import CoreImage
import CoreMedia
import CoreVideo
import CryptoKit
import Darwin
import Foundation
import Network
import ScreenCaptureKit
import VirtualDisplay

enum HostError: LocalizedError {
    case noDisplay, viewerDisconnected, pairingRequired, virtualDisplayUnavailable, screenLocked

    var errorDescription: String? {
        switch self {
        case .noDisplay: "No capturable display. Connect a monitor or an HDMI dummy."
        case .viewerDisconnected: "Viewer disconnected."
        case .pairingRequired: "Remote viewer needs pairing."
        case .virtualDisplayUnavailable: "macOS could not create or capture the Galaxy Tab virtual display."
        case .screenLocked: "The Mac desktop is locked."
        }
    }
}

@MainActor
final class Host {
    private let listener: NWListener
    private var client: NWConnection?

    private let inputToken: Data?
    private let requiresPairing: Bool
    private let inputAvailable: Bool
    private let useJPEG: Bool
    private let useVirtualDisplay: Bool

    init(bindAddress: IPv4Address?, allowedPeer: IPv4Address?, port: NWEndpoint.Port, inputToken: Data?,
         requiresPairing: Bool, inputAvailable: Bool, useJPEG: Bool, useVirtualDisplay: Bool) throws {
        self.inputToken = inputToken
        self.requiresPairing = requiresPairing
        self.inputAvailable = inputAvailable
        self.useJPEG = useJPEG
        self.useVirtualDisplay = useVirtualDisplay
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        if let bindAddress {
            parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(bindAddress), port: port)
            listener = try NWListener(using: parameters)
        } else {
            listener = try NWListener(using: parameters, on: port)
        }

        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("Listening on \(bindAddress.map { String(describing: $0) } ?? "all interfaces"):\(port.rawValue)")
            case .failed(let error):
                print("Listener failed: \(error.localizedDescription)")
                exit(EXIT_FAILURE)
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            if let allowedPeer {
                guard case .hostPort(host: .ipv4(let peer), port: _) = connection.endpoint,
                      peer == allowedPeer else {
                    connection.cancel()
                    return
                }
            }
            Task { @MainActor in self?.accept(connection) }
        }
        listener.start(queue: .main)
    }

    private func accept(_ connection: NWConnection) {
        guard client == nil else {
            connection.cancel()
            return
        }
        client = connection
        connection.start(queue: .main)
        Task {
            defer {
                connection.cancel()
                client = nil
            }
            do {
                try await stream(to: connection)
            } catch {
                print("Stream ended: \(error.localizedDescription)")
            }
        }
    }

    private func stream(to connection: NWConnection) async throws {
        let handshakeTimeout = Task {
            do {
                try await Task.sleep(for: .seconds(5))
                connection.cancel()
            } catch is CancellationError {}
            catch { connection.cancel() }
        }
        defer { handshakeTimeout.cancel() }
        let hello = try await receive(from: connection, length: 64)
        handshakeTimeout.cancel()
        let requestedExtension = requiresPairing && inputToken.map { token in
            let digest = SHA256.hash(data: Data("GalaxySidecar virtual display:".utf8) + token)
            return Data(digest.map { String(format: "%02x", $0) }.joined().utf8) == hello
        } == true
        let paired = inputToken == hello || requestedExtension
        if requiresPairing && !paired {
            try await send(Data([0x47, 0x53, 0x43, 0x32, 2]), to: connection)
            throw HostError.pairingRequired
        }
        let inputEnabled = paired && inputAvailable
        let extendedInput = inputEnabled && !useJPEG
        if desktopIsLocked() {
            try await send(Data([0x47, 0x53, 0x43, 0x32, 32]), to: connection)
            throw HostError.screenLocked
        }

        var extend = useVirtualDisplay || requestedExtension
        var selectedDisplay: SCDisplay?
        if !extend {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                selectedDisplay = content.displays.first { $0.displayID == CGMainDisplayID() } ?? content.displays.first
            } catch {
                if !paired { throw error }
            }
            if selectedDisplay == nil, paired {
                print("No physical display is capturable. Creating a display for the paired viewer.")
                extend = true
            }
        }
        var virtualID: CGDirectDisplayID = 0
        let virtualDisplay = extend ? GSCreateVirtualDisplay(2800, 1752, &virtualID) : nil
        if extend && virtualDisplay == nil {
            try await send(Data([0x47, 0x53, 0x43, 0x32, 16]), to: connection)
            throw HostError.virtualDisplayUnavailable
        }
        defer { withExtendedLifetime(virtualDisplay) {} }

        for attempt in 0..<(extend ? 15 : 0) {
            if attempt > 0 { try await Task.sleep(for: .milliseconds(200)) }
            if let content = try? await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true) {
                selectedDisplay = content.displays.first { $0.displayID == virtualID }
            }
            if selectedDisplay != nil { break }
        }
        guard let display = selectedDisplay else {
            if extend { try await send(Data([0x47, 0x53, 0x43, 0x32, 16]), to: connection) }
            throw extend ? HostError.virtualDisplayUnavailable : HostError.noDisplay
        }
        if extend {
            let mode = CGDisplayCopyDisplayMode(display.displayID)
            print("Extended Galaxy Tab display \(display.displayID): \(display.width)×\(display.height) points, \(mode?.pixelWidth ?? 0)×\(mode?.pixelHeight ?? 0) pixels")
        }

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        let sourceWidth = extend ? 2800 : display.width
        let sourceHeight = extend ? 1752 : display.height
        let scale = min(1, (useJPEG ? 1280.0 : 1920.0) / Double(max(sourceWidth, sourceHeight)))
        configuration.width = max(2, Int(Double(sourceWidth) * scale) / 2 * 2)
        configuration.height = max(2, Int(Double(sourceHeight) * scale) / 2 * 2)
        let encoder: H264Encoder?
        do {
            encoder = useJPEG ? nil : try H264Encoder(width: configuration.width, height: configuration.height)
        } catch {
            print("Hardware H.264 unavailable. Falling back to JPEG. \(error.localizedDescription)")
            encoder = nil
        }
        defer { encoder?.close() }
        configuration.showsCursor = true
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        configuration.queueDepth = 3
        configuration.pixelFormat = encoder == nil
            ? kCVPixelFormatType_32BGRA : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange

        let (frames, frameSink) = AsyncStream<Frame>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let capture = Capture(frames: frameSink) { connection.cancel() }
        let stream = SCStream(filter: filter, configuration: configuration, delegate: capture)
        try stream.addStreamOutput(capture, type: .screen, sampleHandlerQueue: DispatchQueue(label: "GalaxySidecar capture"))

        let pointer = inputEnabled ? Pointer(display: display.displayID) : nil
        let keyboard = inputEnabled ? Keyboard() : nil
        let window = encoder == nil ? 1 : 2
        let (acks, ackSink) = AsyncStream<UInt64>.makeStream(bufferingPolicy: .bufferingNewest(window))
        let receiver = Task {
            defer {
                pointer?.release()
                keyboard?.release()
                ackSink.finish()
                frameSink.finish()
                connection.cancel()
            }
            do {
                while true {
                    let message = try await receive(from: connection, length: 13)
                    if message.first == 0 {
                        ackSink.yield(DispatchTime.now().uptimeNanoseconds)
                    } else if message.first == 8 {
                        let length = message.withUnsafeBytes {
                            Int(UInt32(bigEndian: $0.loadUnaligned(fromByteOffset: 1, as: UInt32.self)))
                        }
                        guard extendedInput, (1...Clipboard.limit).contains(length) else { break }
                        Clipboard.write(try await receive(from: connection, length: length))
                    } else if message.first == 9, extendedInput {
                        try await send(Clipboard.response(), to: connection)
                    } else if let kind = message.first, (5...7).contains(kind) || kind == 16 {
                        keyboard?.handle(message)
                    } else {
                        pointer?.handle(message)
                    }
                }
            } catch {
                return
            }
        }
        defer { receiver.cancel(); connection.cancel() }

        let capabilities: UInt8 = (inputEnabled ? 1 : 0) | (extendedInput ? 8 : 0) | (encoder == nil ? 0 : 4)
        var header = Data([0x47, 0x53, 0x43, 0x32, capabilities])
        if encoder != nil {
            for dimension in [configuration.width, configuration.height] {
                var value = UInt16(dimension).bigEndian
                withUnsafeBytes(of: &value) { header.append(contentsOf: $0) }
            }
        }
        try await send(header, to: connection)
        print("Streaming \(encoder == nil ? "JPEG" : "hardware H.264") at \(configuration.width)×\(configuration.height)")
        var heartbeat: Task<Void, Never>?
        defer { heartbeat?.cancel() }
        try await stream.startCapture()
        do {
            var acknowledgements = acks.makeAsyncIterator()
            var samples: [Double] = []
            var delivery: [Double] = []
            var bytesSent = 0
            var congestedWindows = 0
            var clearWindows = 0
            var pending: [(captured: UInt64, sent: UInt64, size: Int)] = []
            let context = CIContext(options: [.cacheIntermediates: false])
            var periodStart = DispatchTime.now().uptimeNanoseconds
            for await frame in frames {
                let packet: Data?
                if let encoder {
                    packet = try await encoder.encode(frame)
                } else {
                    packet = await Task.detached { frame.encode(using: context) }.value
                }
                guard let packet else { continue }
                let sendStart = DispatchTime.now().uptimeNanoseconds
                try await send(packet, to: connection)
                if heartbeat == nil {
                    heartbeat = Task {
                        while !Task.isCancelled {
                            do {
                                try await Task.sleep(for: .seconds(2))
                                if desktopIsLocked() {
                                    connection.cancel()
                                    return
                                }
                                if extendedInput {
                                    try await send(Data([0, 0, 0, 0, 255, 255, 255, 255]), to: connection)
                                }
                            } catch { return }
                        }
                    }
                }
                pending.append((frame.timestamp, sendStart, packet.count))
                guard pending.count == window else { continue }
                guard let now = await acknowledgements.next() else { break }
                let acknowledged = pending.removeFirst()
                guard now >= acknowledged.sent else { throw HostError.viewerDisconnected }
                samples.append(Double(now - acknowledged.captured) / 1_000_000)
                delivery.append(Double(now - acknowledged.sent) / 1_000_000)
                bytesSent += acknowledged.size
                if samples.count == 60 {
                    let sorted = samples.sorted()
                    let seconds = Double(now - periodStart) / 1_000_000_000
                    encoder?.adapt(roundTripMillis: delivery.sorted()[56],
                                   congestedWindows: &congestedWindows, clearWindows: &clearWindows)
                    print(String(format: "%.1f FPS | p50 %.1f ms, p95 %.1f ms | %.2f Mbps on wire | %.2f Mbps target",
                                 Double(samples.count) / seconds, sorted[29], sorted[56],
                                 Double(bytesSent) * 8 / seconds / 1_000_000,
                                 Double(encoder?.bitrate ?? 0) / 1_000_000))
                    samples.removeAll(keepingCapacity: true)
                    delivery.removeAll(keepingCapacity: true)
                    bytesSent = 0
                    periodStart = now
                }
            }
        } catch {
            try? await stream.stopCapture()
            throw error
        }
        try? await stream.stopCapture()
    }

    private func send(_ data: Data, to connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            })
        }
    }

    private func receive(from connection: NWConnection, length: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: length, maximumLength: length) { data, _, _, error in
                if let data, data.count == length { continuation.resume(returning: data) }
                else { continuation.resume(throwing: error ?? HostError.viewerDisconnected) }
            }
        }
    }
}

func desktopIsLocked() -> Bool {
    let session = CGSessionCopyCurrentDictionary() as? [String: Any]
    return session?["CGSSessionScreenIsLocked"] as? Bool == true
}

let rawArguments = Array(CommandLine.arguments.dropFirst())
let useJPEG = rawArguments.contains("--jpeg")
let useVirtualDisplay = rawArguments.contains("--virtual-display")
let arguments = rawArguments.filter { $0 != "--jpeg" && $0 != "--virtual-display" }
let loopback = arguments.contains("--loopback")
let remote = arguments.first == "--remote"
let inputEnabled = remote || arguments.contains("--input")
let bindAddress: IPv4Address?
let allowedPeer: IPv4Address?
if arguments.count == 3, (remote || arguments[0] == "--lan-view"),
   let local = IPv4Address(arguments[1]), let peer = IPv4Address(arguments[2]),
   !remote || (isTailnetAddress(local) && isTailnetAddress(peer)) {
    bindAddress = local
    allowedPeer = peer
} else if arguments.isEmpty || arguments == ["--loopback"] ||
          arguments == ["--loopback", "--input"] || arguments == ["--input", "--loopback"] {
    bindAddress = loopback ? IPv4Address("127.0.0.1") : nil
    allowedPeer = nil
} else {
    print("Usage: sidecar-host [--jpeg] [--virtual-display] [--loopback [--input] | --lan-view MAC_IPV4 TABLET_IPV4 | --remote MAC_TAILNET_IPV4 TABLET_TAILNET_IPV4]")
    exit(EXIT_FAILURE)
}
let background = ProcessInfo.processInfo.environment["GALAXYSIDECAR_BACKGROUND"] == "1"
guard CGPreflightScreenCaptureAccess() || (!background && CGRequestScreenCaptureAccess()) else {
    print("Grant Screen & System Audio Recording permission, then relaunch the host.")
    exit(background ? EXIT_SUCCESS : EXIT_FAILURE)
}
func isTailnetAddress(_ address: IPv4Address) -> Bool {
    let bytes = Array(address.rawValue)
    return bytes[0] == 100 && (64...127).contains(bytes[1])
}

var inputAvailable = inputEnabled && AXIsProcessTrusted()
if inputEnabled, !inputAvailable, !background {
    let prompt = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
    inputAvailable = AXIsProcessTrustedWithOptions(prompt)
    if !inputAvailable {
        print("Grant Accessibility to the host for input. Running in view-only mode for now.")
    }
}
do {
    var token: Data?
    if inputEnabled {
        let temporary = FileManager.default.temporaryDirectory.appending(path: "galaxysidecar-\(getuid())")
        let directory = remote
            ? FileManager.default.homeDirectoryForCurrentUser.appending(path: "Library/Application Support/GalaxySidecar")
            : temporary
        let tokenFile = directory.appending(path: remote ? "remote-input-token" : "input-token")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        if remote, let saved = try? Data(contentsOf: tokenFile), saved.count == 64 {
            token = saved
        } else {
            let legacy = try? Data(contentsOf: temporary.appending(path: "remote-input-token"))
            let secret = (UUID().uuidString + UUID().uuidString).replacingOccurrences(of: "-", with: "")
            let credentials = remote && legacy?.count == 64 ? (legacy ?? Data(secret.utf8)) : Data(secret.utf8)
            try credentials.write(to: tokenFile, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: tokenFile.path)
            token = credentials
        }
    }
    let host = try Host(bindAddress: bindAddress, allowedPeer: allowedPeer, port: remote ? 5556 : 5555,
                        inputToken: token, requiresPairing: remote, inputAvailable: inputAvailable, useJPEG: useJPEG, useVirtualDisplay: useVirtualDisplay)
    withExtendedLifetime(host) { dispatchMain() }
} catch {
    print("Could not start host: \(error.localizedDescription)")
    exit(EXIT_FAILURE)
}
