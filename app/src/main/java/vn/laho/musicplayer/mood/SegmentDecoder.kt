// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes a mono excerpt of a track with the platform decoders (MediaExtractor +
 * MediaCodec). For songs longer than a minute the excerpt starts at 30 % of the duration to
 * skip intros. Formats without a platform decoder (e.g. APE, DSD) return null; the LLM can
 * still classify those songs from metadata.
 */
class SegmentDecoder(private val context: Context) {

    data class Pcm(val mono: FloatArray, val sampleRate: Int)

    fun decode(uri: String, durationMs: Long, seconds: Double = EXCERPT_SECONDS): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            if (durationMs > 60_000) extractor.seekTo(durationMs * 300L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val wanted = (seconds * sampleRate).toInt()
            var out = FloatArray(wanted)
            var written = 0
            var inputDone = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var idleLoops = 0

            while (written < out.size && idleLoops < MAX_IDLE_LOOPS) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        val newWanted = (seconds * sampleRate).toInt()
                        if (newWanted != out.size && written == 0) out = FloatArray(newWanted)
                    }
                    outIndex >= 0 -> {
                        idleLoops = 0
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset).limit(info.offset + info.size)
                        written = appendMono(buf, encoding, channels, out, written)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> idleLoops++
                }
            }
            if (written < sampleRate * MIN_SECONDS) return null
            return Pcm(if (written == out.size) out else out.copyOf(written), sampleRate)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun appendMono(buf: java.nio.ByteBuffer, encoding: Int, channels: Int, out: FloatArray, start: Int): Int {
        var w = start
        val ch = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            while (fb.remaining() >= ch && w < out.size) {
                var s = 0f
                repeat(ch) { s += fb.get() }
                out[w++] = s / ch
            }
        } else {
            val sb = buf.asShortBuffer()
            while (sb.remaining() >= ch && w < out.size) {
                var s = 0f
                repeat(ch) { s += sb.get() / 32768f }
                out[w++] = s / ch
            }
        }
        return w
    }

    companion object {
        const val EXCERPT_SECONDS = 30.0
        private const val MIN_SECONDS = 5
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_LOOPS = 500
    }
}
