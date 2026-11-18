import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

enum H264Error: LocalizedError {
    case videoToolbox(OSStatus)
    case invalidSample

    var errorDescription: String? {
        switch self {
        case .videoToolbox(let code): "VideoToolbox failed (\(code))."
        case .invalidSample: "VideoToolbox returned an invalid H.264 packet."
        }
    }
}

@MainActor
final class H264Encoder {
    private let session: VTCompressionSession
    private(set) var bitrate = 3_000_000

    init(width: Int, height: Int) throws {
        var created: VTCompressionSession?
        let specification = [
            kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: kCFBooleanTrue,
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: kCFBooleanTrue,
        ] as CFDictionary
        let result = VTCompressionSessionCreate(
            allocator: nil, width: Int32(width), height: Int32(height),
            codecType: kCMVideoCodecType_H264, encoderSpecification: specification,
            imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &created
        )
        guard result == noErr, let created else { throw H264Error.videoToolbox(result) }
        session = created
        try set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        try set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        try set(kVTCompressionPropertyKey_ExpectedFrameRate, 60 as CFNumber)
        try set(kVTCompressionPropertyKey_MaxKeyFrameInterval, 120 as CFNumber)
        try set(kVTCompressionPropertyKey_AverageBitRate, bitrate as CFNumber)
        let prepared = VTCompressionSessionPrepareToEncodeFrames(created)
        guard prepared == noErr else { throw H264Error.videoToolbox(prepared) }
    }

    func close() { VTCompressionSessionInvalidate(session) }

    func adapt(roundTripMillis: Double, congestedWindows: inout Int, clearWindows: inout Int) {
        congestedWindows = roundTripMillis > 65 ? congestedWindows + 1 : 0
        clearWindows = roundTripMillis < 32 ? clearWindows + 1 : 0
        let next: Int
        if congestedWindows >= 2 {
            next = max(900_000, bitrate * 3 / 4)
            congestedWindows = 0
        } else if clearWindows >= 3 {
            next = min(7_000_000, bitrate + 500_000)
            clearWindows = 0
        } else { return }
        guard next != bitrate else { return }
        do {
            try set(kVTCompressionPropertyKey_AverageBitRate, next as CFNumber)
            bitrate = next
        } catch {
            print("Could not update H.264 bitrate: \(error.localizedDescription)")
        }
    }

    func encode(_ frame: Frame) async throws -> Data? {
        guard let image = frame.sample.imageBuffer else { return nil }
        return try await withCheckedThrowingContinuation { continuation in
            var flags: VTEncodeInfoFlags = []
            let status = VTCompressionSessionEncodeFrame(
                session, imageBuffer: image,
                presentationTimeStamp: frame.sample.presentationTimeStamp,
                duration: .invalid, frameProperties: nil, infoFlagsOut: &flags
            ) { status, flags, sample in
                if status != noErr {
                    continuation.resume(throwing: H264Error.videoToolbox(status))
                } else if flags.contains(.frameDropped) {
                    continuation.resume(returning: nil)
                } else if let sample, let packet = Self.packet(for: sample) {
                    continuation.resume(returning: packet)
                } else {
                    continuation.resume(throwing: H264Error.invalidSample)
                }
            }
            if status != noErr { continuation.resume(throwing: H264Error.videoToolbox(status)) }
        }
    }

    private func set(_ key: CFString, _ value: CFTypeRef) throws {
        let status = VTSessionSetProperty(session, key: key, value: value)
        guard status == noErr else { throw H264Error.videoToolbox(status) }
    }

    nonisolated private static func packet(for sample: CMSampleBuffer) -> Data? {
        guard let format = sample.formatDescription, let block = sample.dataBuffer else { return nil }
        let startCode: [UInt8] = [0, 0, 0, 1]
        var annexB = Data()
        let sampleAttachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
            as? [[String: Any]]
        let isKeyFrame = sampleAttachments?.first?[kCMSampleAttachmentKey_NotSync as String] as? Bool != true
        if isKeyFrame {
            for index in 0..<2 {
                var bytes: UnsafePointer<UInt8>?
                var size = 0
                guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: index, parameterSetPointerOut: &bytes,
                    parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
                ) == noErr, let bytes else { return nil }
                annexB.append(contentsOf: startCode)
                annexB.append(bytes, count: size)
            }
        }

        var source = Data(count: CMBlockBufferGetDataLength(block))
        let copied = source.withUnsafeMutableBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            return CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: raw.count, destination: base)
        }
        guard copied == noErr else { return nil }

        var offset = 0
        while offset + 4 <= source.count {
            let length = source.withUnsafeBytes {
                Int(UInt32(bigEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)))
            }
            offset += 4
            guard length > 0, length <= source.count - offset else { return nil }
            annexB.append(contentsOf: startCode)
            annexB.append(source[offset..<(offset + length)])
            offset += length
        }
        guard offset == source.count, (1...8_388_608).contains(annexB.count) else { return nil }
        var length = UInt32(annexB.count).bigEndian
        var packet = withUnsafeBytes(of: &length) { Data($0) }
        packet.append(annexB)
        return packet
    }
}
