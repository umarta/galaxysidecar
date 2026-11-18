package dev.galaxysidecar

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

internal class AvcDecoder(
    width: Int,
    height: Int,
    firstFrame: ByteArray,
    surface: Surface
) : Closeable {
    private val codec: MediaCodec
    private val output = MediaCodec.BufferInfo()
    private var frames = 0L

    init {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(parameterSet(firstFrame, 7)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(parameterSet(firstFrame, 8)))
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                !info.isEncoder && info.supportedTypes.contains(mime) &&
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
            }
        val preferred = if (Build.VERSION.SDK_INT >= 29) {
            candidates.firstOrNull { it.isHardwareAccelerated }
        } else null
        val decoder = preferred?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createDecoderByType(mime)

        try {
            val capabilities = decoder.codecInfo.getCapabilitiesForType(mime)
            if (Build.VERSION.SDK_INT >= 30 &&
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (error: RuntimeException) {
            decoder.release()
            throw IOException("This device could not start its H.264 decoder. Try the Mac host with --jpeg.", error)
        }
        codec = decoder
        val acceleration = if (Build.VERSION.SDK_INT >= 29 && codec.codecInfo.isHardwareAccelerated) "hardware" else "default"
        Log.i("GalaxySidecar", "H.264 decoder: ${codec.name} ($acceleration)")
    }

    fun render(bytes: ByteArray) {
        try {
            val input = codec.dequeueInputBuffer(250_000)
            if (input < 0) throw IOException("The H.264 decoder has no available input buffer.")
            val target = codec.getInputBuffer(input) ?: throw IOException("The H.264 input buffer is unavailable.")
            if (bytes.size > target.capacity()) throw IOException("The H.264 frame exceeds the decoder buffer.")
            target.clear()
            target.put(bytes)
            codec.queueInputBuffer(input, 0, bytes.size, frames++ * 16_667, 0)

            while (true) {
                val index = codec.dequeueOutputBuffer(output, 250_000)
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true)
                    return
                }
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    throw IOException("The H.264 frame did not decode in time. Reconnect to the Mac.")
                }
            }
        } catch (error: IllegalStateException) {
            throw IOException("The H.264 decoder stopped. Reconnect to the Mac.", error)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun parameterSet(packet: ByteArray, type: Int): ByteArray {
        var start = startCode(packet, 0)
        while (start >= 0 && start + 4 < packet.size) {
            val next = startCode(packet, start + 4)
            if (packet[start + 4].toInt() and 0x1f == type) {
                return packet.copyOfRange(start, if (next == -1) packet.size else next)
            }
            start = next
        }
        throw IOException("The H.264 stream is missing its decoder configuration.")
    }

    private fun startCode(packet: ByteArray, offset: Int): Int {
        for (index in offset..packet.size - 4) {
            if (packet[index] == 0.toByte() && packet[index + 1] == 0.toByte() &&
                packet[index + 2] == 0.toByte() && packet[index + 3] == 1.toByte()) return index
        }
        return -1
    }
}
