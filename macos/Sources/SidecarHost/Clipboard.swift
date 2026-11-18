import AppKit
import Foundation

@MainActor
enum Clipboard {
    static let limit = 65_536

    static func write(_ data: Data) {
        guard let text = String(data: data, encoding: .utf8) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    static func response() -> Data {
        let text = NSPasteboard.general.string(forType: .string)
        let payload = text.map { Data($0.utf8) } ?? Data()
        let count = payload.count <= limit ? payload.count : 0
        var packet = Data(repeating: 0, count: 4)
        var size = UInt32(count).bigEndian
        withUnsafeBytes(of: &size) { packet.append(contentsOf: $0) }
        if count > 0 { packet.append(payload) }
        return packet
    }
}
