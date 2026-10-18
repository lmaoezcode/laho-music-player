// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio sink that runs LaHo's 32-bit float DSP before handing audio to [delegate].
 *
 * Why this exists: Media3's DefaultAudioSink either skips every AudioProcessor (float output
 * enabled) or truncates all PCM to 16-bit before processing (float output disabled), so
 * hi-res audio cannot be equalised without quality loss. This sink converts any linear PCM
 * input (8/16/24/32-bit int or float) to float itself, applies the [peq], and always feeds
 * the delegate float PCM. The delegate must be built with float output enabled.
 *
 * Frame counts are never changed, so timestamps, gapless trimming (handled downstream by the
 * delegate using the format's encoder delay/padding) and position reporting stay correct.
 * Non-PCM input (passthrough / offload) bypasses the DSP untouched.
 */
@OptIn(UnstableApi::class)
class LahoDspAudioSink(
    delegate: AudioSink,
    private val peq: PeqProcessor,
    private val onClipped: (Int) -> Unit = {},
    private val onRelease: () -> Unit = {},
) : ForwardingAudioSink(delegate) {

    private var active = false
    private var inputFormat: PcmFormat = PcmFormat.S16_LE
    private var channelCount = 2

    private var scratch = FloatArray(0)
    private var output: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var hasPendingOutput = false
    private var pendingPresentationTimeUs = 0L

    override fun supportsFormat(format: Format): Boolean =
        super.supportsFormat(asFloatFormat(format) ?: format)

    override fun getFormatSupport(format: Format): Int =
        super.getFormatSupport(asFloatFormat(format) ?: format)

    @Suppress("DEPRECATION") // Copying the deprecated buffer size override verbatim.
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val pcm = pcmFormatOf(format)
        if (pcm == null) {
            active = false
            super.configure(audioSinkConfig)
            return
        }
        active = true
        inputFormat = pcm
        channelCount = format.channelCount
        peq.configure(format.sampleRate, format.channelCount)

        val floatFormat = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        val bufferOverride = audioSinkConfig.preferredBufferSizeOverride.let {
            if (it <= 0) it else it / pcm.bytesPerSample * FLOAT_BYTES
        }
        val floatConfig = AudioSink.AudioSinkConfig.Builder(floatFormat)
            .setPreferredBufferSizeOverride(bufferOverride)
            .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
            .setTimeline(audioSinkConfig.timeline)
            .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
            .build()
        super.configure(floatConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        if (!hasPendingOutput) {
            if (!buffer.hasRemaining()) return true
            convertAndProcess(buffer)
            pendingPresentationTimeUs = presentationTimeUs
            hasPendingOutput = true
        }
        // The delegate requires the same ByteBuffer object until it is fully consumed,
        // which holds because `output` is only refilled after that happens.
        val consumed = super.handleBuffer(output, pendingPresentationTimeUs, encodedAccessUnitCount)
        if (consumed) hasPendingOutput = false
        return consumed && !buffer.hasRemaining()
    }

    override fun flush() {
        dropPending()
        super.flush()
    }

    override fun reset() {
        dropPending()
        active = false
        super.reset()
    }

    override fun release() {
        onRelease()
        super.release()
    }

    private fun dropPending() {
        hasPendingOutput = false
        output.clear().limit(0)
        peq.reset()
    }

    private fun convertAndProcess(input: ByteBuffer) {
        val frameBytes = inputFormat.bytesPerSample * channelCount
        val usableBytes = input.remaining() / frameBytes * frameBytes
        val samples = usableBytes / inputFormat.bytesPerSample
        if (scratch.size < samples) scratch = FloatArray(samples)

        val view = input.duplicate().limit(input.position() + usableBytes) as ByteBuffer
        PcmConverter.toFloat(view, inputFormat, scratch)
        // Consume everything, dropping a trailing partial frame if a decoder ever emits one.
        input.position(input.limit())

        peq.process(scratch, samples / channelCount)

        var clipped = 0
        for (i in 0 until samples) {
            val s = scratch[i]
            if (s > 1f) { scratch[i] = 1f; clipped++ }
            else if (s < -1f) { scratch[i] = -1f; clipped++ }
        }
        if (clipped > 0) onClipped(clipped)

        val bytes = samples * FLOAT_BYTES
        if (output.capacity() < bytes) {
            output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        output.clear()
        output.asFloatBuffer().put(scratch, 0, samples)
        output.limit(bytes)
    }

    companion object {
        private const val FLOAT_BYTES = 4

        /** Maps a raw PCM [Format] to the converter's layout, or null if the DSP can't take it. */
        fun pcmFormatOf(format: Format): PcmFormat? {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
            if (format.channelCount <= 0 || format.sampleRate <= 0) return null
            return when (format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> PcmFormat.U8
                C.ENCODING_PCM_16BIT -> PcmFormat.S16_LE
                C.ENCODING_PCM_16BIT_BIG_ENDIAN -> PcmFormat.S16_BE
                C.ENCODING_PCM_24BIT -> PcmFormat.S24_LE
                C.ENCODING_PCM_24BIT_BIG_ENDIAN -> PcmFormat.S24_BE
                C.ENCODING_PCM_32BIT -> PcmFormat.S32_LE
                C.ENCODING_PCM_32BIT_BIG_ENDIAN -> PcmFormat.S32_BE
                C.ENCODING_PCM_FLOAT -> PcmFormat.F32
                else -> null
            }
        }

        private fun asFloatFormat(format: Format): Format? =
            if (pcmFormatOf(format) == null) null
            else format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
    }
}
