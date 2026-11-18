import CoreImage
import CoreMedia
import Foundation
import ImageIO
import ScreenCaptureKit

// ScreenCaptureKit owns the surface. Retain the sample and only read its pixel buffer.
struct Frame: @unchecked Sendable {
    let timestamp: UInt64
    let sample: CMSampleBuffer

    func encode(using context: CIContext) -> Data? {
        guard let buffer = sample.imageBuffer,
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }

        let image = CIImage(cvPixelBuffer: buffer)
        let quality = CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String)
        guard let jpeg = context.jpegRepresentation(of: image, colorSpace: colorSpace, options: [quality: 0.60]),
              (1...8_388_608).contains(jpeg.count) else { return nil }
        var length = UInt32(jpeg.count).bigEndian
        var packet = withUnsafeBytes(of: &length) { Data($0) }
        packet.append(jpeg)
        return packet
    }
}

final class Capture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let frames: AsyncStream<Frame>.Continuation
    private let onFailure: @Sendable () -> Void

    init(frames: AsyncStream<Frame>.Continuation, onFailure: @escaping @Sendable () -> Void) {
        self.frames = frames
        self.onFailure = onFailure
    }

    func stream(_ stream: SCStream, didStopWithError error: any Error) {
        print("Capture stopped: \(error.localizedDescription)")
        frames.finish()
        onFailure()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen, sample.imageBuffer != nil else { return }
        frames.yield(Frame(timestamp: DispatchTime.now().uptimeNanoseconds, sample: sample))
    }
}
