import CoreGraphics
import Foundation

@MainActor
final class Keyboard {
    private var held: Set<CGKeyCode> = []

    func handle(_ message: Data) {
        let kind = message[message.startIndex]
        let value = word(message, offset: 1)
        if kind == 16 {
            shortcut(value)
        } else if kind == 5, value == 10 || value == 9 {
            let key: CGKeyCode = value == 10 ? 36 : 48
            post(key, down: true, flags: [])
            post(key, down: false, flags: [])
        } else if kind == 5, let scalar = Unicode.Scalar(value), scalar.value >= 32 {
            let text = Array(String(scalar).utf16)
            for down in [true, false] {
                let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
                event?.keyboardSetUnicodeString(stringLength: text.count, unicodeString: text)
                event?.post(tap: .cghidEventTap)
            }
        } else if kind == 6 || kind == 7, let key = Self.virtualKeys[value] {
            let down = kind == 6
            if down { held.insert(key) } else { held.remove(key) }
            let androidFlags = word(message, offset: 5)
            var flags: CGEventFlags = []
            if androidFlags & 1 != 0 { flags.insert(.maskShift) }
            if androidFlags & 2 != 0 { flags.insert(.maskAlternate) }
            if androidFlags & 0x1000 != 0 { flags.insert(.maskControl) }
            if androidFlags & 0x10000 != 0 { flags.insert(.maskCommand) }
            post(key, down: down, flags: flags)
        }
    }

    private func shortcut(_ index: UInt32) {
        let keys: [CGKeyCode] = [48, 49, 53]
        guard index < keys.count else { return }
        let key = keys[Int(index)]
        let modifiers: CGEventFlags = index < 2 ? .maskCommand : []
        if index < 2 { commandModifier(pressed: true) }
        post(key, down: true, flags: modifiers)
        post(key, down: false, flags: modifiers)
        if index < 2 { commandModifier(pressed: false) }
    }

    private func commandModifier(pressed: Bool) {
        let event = CGEvent(keyboardEventSource: nil, virtualKey: 55, keyDown: pressed)
        event?.type = .flagsChanged
        event?.flags = pressed ? .maskCommand : []
        event?.post(tap: .cghidEventTap)
    }

    func release() {
        for key in held { post(key, down: false, flags: []) }
        held.removeAll()
    }

    private func post(_ key: CGKeyCode, down: Bool, flags: CGEventFlags) {
        let event = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: down)
        event?.flags = flags
        event?.post(tap: .cghidEventTap)
    }

    private func word(_ data: Data, offset: Int) -> UInt32 {
        data.withUnsafeBytes { UInt32(bigEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) }
    }

    // Android KeyEvent constants mapped to macOS ANSI virtual key codes.
    private static let virtualKeys: [UInt32: CGKeyCode] = [
        7: 29, 8: 18, 9: 19, 10: 20, 11: 21, 12: 23, 13: 22, 14: 26, 15: 28, 16: 25,
        29: 0, 30: 11, 31: 8, 32: 2, 33: 14, 34: 3, 35: 5, 36: 4, 37: 34,
        38: 38, 39: 40, 40: 37, 41: 46, 42: 45, 43: 31, 44: 35, 45: 12, 46: 15,
        47: 1, 48: 17, 49: 32, 50: 9, 51: 13, 52: 7, 53: 16, 54: 6,
        19: 126, 20: 125, 21: 123, 22: 124, 55: 43, 56: 47, 61: 48, 62: 49,
        66: 36, 67: 51, 68: 50, 69: 27, 70: 24, 71: 33, 72: 30, 73: 42, 74: 41,
        75: 39, 76: 44, 92: 116, 93: 121, 111: 53, 112: 117, 122: 115, 123: 119,
        131: 122, 132: 120, 133: 99, 134: 118, 135: 96, 136: 97,
        137: 98, 138: 100, 139: 101, 140: 109, 141: 103, 142: 111
    ]
}
