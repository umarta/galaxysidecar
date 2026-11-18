import CoreGraphics
import Foundation

@MainActor
final class Pointer {
    private let bounds: CGRect
    private var pressed = false
    private var pen = false
    private var position = CGPoint.zero

    init(display: CGDirectDisplayID) {
        bounds = CGDisplayBounds(display)
    }

    func handle(_ message: Data) {
        let kind = message[message.startIndex]
        let x = readFloat(message, at: 1)
        let y = readFloat(message, at: 5)
        let pressure = readFloat(message, at: 9)
        if (10...15).contains(kind) {
            trackpad(kind, dx: x, dy: y)
            return
        }
        guard (0...1).contains(x), (0...1).contains(y), (0...1).contains(pressure) else { return }
        position = CGPoint(
            x: bounds.minX + CGFloat(x) * max(0, bounds.width - 1),
            y: bounds.minY + CGFloat(y) * max(0, bounds.height - 1)
        )
        pen = kind & 0x80 != 0
        switch kind & 0x7f {
        case 1 where !pressed:
            pressed = true
            post(.leftMouseDown, pressure: pressure)
        case 2 where pressed:
            post(.leftMouseDragged, pressure: pressure)
        case 3:
            release()
        case 4 where !pressed:
            post(.mouseMoved, pressure: 0)
        default:
            break
        }
    }

    private func trackpad(_ kind: UInt8, dx: Double, dy: Double) {
        guard dx.isFinite, dy.isFinite else { return }
        let cursor = CGEvent(source: nil)?.location ?? position
        if kind != 15 {
            let base = bounds.contains(cursor) ? cursor : CGPoint(x: bounds.midX, y: bounds.midY)
            position = CGPoint(
                x: max(bounds.minX, min(bounds.maxX - 1, base.x + (kind == 10 ? dx * bounds.width : 0))),
                y: max(bounds.minY, min(bounds.maxY - 1, base.y + (kind == 10 ? dy * bounds.height : 0)))
            )
        }
        pen = false
        switch kind {
        case 10:
            post(pressed ? .leftMouseDragged : .mouseMoved, pressure: pressed ? 1 : 0)
        case 11:
            let vertical = Int32(max(-150, min(150, dy * 2)).rounded())
            let horizontal = Int32(max(-150, min(150, dx * 2)).rounded())
            let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                                wheel1: vertical, wheel2: horizontal, wheel3: 0)
            event?.location = position
            event?.post(tap: .cghidEventTap)
        case 12 where !pressed:
            post(.rightMouseDown, pressure: 1, button: .right)
            post(.rightMouseUp, pressure: 0, button: .right)
        case 13 where !pressed:
            post(.leftMouseDown, pressure: 1)
            post(.leftMouseUp, pressure: 0)
        case 14 where !pressed:
            pressed = true
            post(.leftMouseDown, pressure: 1)
        case 15:
            release()
        default:
            break
        }
    }

    func release() {
        guard pressed else { return }
        pressed = false
        post(.leftMouseUp, pressure: 0)
    }

    private func post(_ type: CGEventType, pressure: Double, button: CGMouseButton = .left) {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type,
                                  mouseCursorPosition: position, mouseButton: button) else { return }
        if pen {
            event.setIntegerValueField(.mouseEventSubtype, value: Int64(CGEventMouseSubtype.tabletPoint.rawValue))
            event.setDoubleValueField(.tabletEventPointPressure, value: pressure)
            event.setIntegerValueField(.tabletEventPointButtons, value: pressed ? 1 : 0)
        }
        event.setDoubleValueField(.mouseEventPressure, value: pressure)
        event.setIntegerValueField(.mouseEventClickState, value: 1)
        event.post(tap: .cghidEventTap)
    }

    private func readFloat(_ data: Data, at offset: Int) -> Double {
        data.withUnsafeBytes {
            Double(Float(bitPattern: UInt32(bigEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self))))
        }
    }
}
