// LaHo addition to the vendored decent-player Media3 wrapper (see README.md,
// "LaHo modifications"). MIT, like the rest of this module. Pure Kotlin: unit-tested from
// app/src/test/java/vn/laho/musicplayer/usb/.
package com.decent.usbaudio.media3

import com.decent.usbaudio.UsbRateRejection

/** Which decoder feeds the USB stream. */
enum class UsbEngine {
    /** No USB stream. */
    NONE,
    /** decent-player's native libFLAC decoder writes straight to the USB ring. */
    NATIVE_FLAC,
    /** ExoPlayer decodes (MediaCodec/FFmpeg) and UsbAudioSink forwards the PCM. */
    EXOPLAYER,
}

/**
 * What [UsbAudioSink] is doing right now, published as a StateFlow for the UI
 * ("signal path" / bit-perfect badge).
 */
data class UsbSignalPath(
    /** True while PCM is streamed to the DAC by this driver. */
    val active: Boolean = false,
    val dacName: String = "",
    /** Rate the stream runs at (= the file's rate; the driver never resamples). */
    val sampleRateHz: Int = 0,
    /** Rate the DAC reports via GET_CUR after SET_CUR; <= 0 if it does not answer. */
    val dacReportedRateHz: Int = 0,
    /** Bits per sample of the decoded source (0 = unknown). */
    val sourceBitDepth: Int = 0,
    /** Bits per sample sent over USB (the DAC's widest alt setting). */
    val usbBitDepth: Int = 0,
    val engine: UsbEngine = UsbEngine.NONE,
    val bitPerfect: Boolean = false,
    /** Set when the last track could not use the driver and went to Android's pipeline. */
    val fallbackRateHz: Int = 0,
    val fallbackReason: UsbRateRejection? = null,
    /** Common rates the DAC's clock source accepts (empty if GET RANGE was not answered). */
    val supportedRates: List<Int> = emptyList(),
) {
    val isFallback: Boolean get() = fallbackReason != null

    companion object {
        val INACTIVE = UsbSignalPath()

        /** Effective bits of float PCM handed over by ExoPlayer (exact for <= 24-bit sources). */
        const val FLOAT_SOURCE_BITS = 24

        /**
         * Bit-perfect means: the stream runs, the DAC clock is at the file's rate (no
         * resampling anywhere), and every source bit fits the USB sample (the LSBs are
         * zero-padded, never truncated). Volume is never applied in USB mode.
         */
        fun isBitPerfect(
            active: Boolean,
            sampleRateHz: Int,
            dacReportedRateHz: Int,
            sourceBitDepth: Int,
            usbBitDepth: Int,
        ): Boolean = active &&
            sampleRateHz > 0 &&
            (dacReportedRateHz <= 0 || dacReportedRateHz == sampleRateHz) &&
            sourceBitDepth in 1..usbBitDepth
    }
}
