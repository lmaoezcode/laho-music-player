// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linear PCM layouts the DSP sink accepts as input. */
enum class PcmFormat(val bytesPerSample: Int) {
    U8(1),
    S16_LE(2),
    S16_BE(2),
    S24_LE(3),
    S24_BE(3),
    S32_LE(4),
    S32_BE(4),
    F32(4),
}

/** Converts integer / float PCM into normalised float samples in [-1, 1). */
object PcmConverter {

    private const val SCALE_8 = 1.0f / 128f
    private const val SCALE_16 = 1.0f / 32768f
    private const val SCALE_24 = 1.0f / 8388608f
    private const val SCALE_32 = 1.0 / 2147483648.0

    /**
     * Reads every whole sample between [src]'s position and limit into [dst], advancing
     * the position. Returns the number of samples written.
     *
     * 8/16/24-bit inputs convert exactly (a float has a 24-bit mantissa); 32-bit integer
     * input is rounded to float precision, as in Media3's own float output path.
     */
    fun toFloat(src: ByteBuffer, format: PcmFormat, dst: FloatArray): Int {
        val count = src.remaining() / format.bytesPerSample
        require(dst.size >= count) { "dst too small: ${dst.size} < $count" }
        when (format) {
            PcmFormat.U8 -> for (i in 0 until count) {
                dst[i] = ((src.get().toInt() and 0xFF) - 128) * SCALE_8
            }
            PcmFormat.S16_LE, PcmFormat.S16_BE -> {
                val order = if (format == PcmFormat.S16_LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val view = src.slice().order(order).asShortBuffer()
                for (i in 0 until count) dst[i] = view.get(i) * SCALE_16
                src.position(src.position() + count * 2)
            }
            PcmFormat.S24_LE -> for (i in 0 until count) {
                val b0 = src.get().toInt() and 0xFF
                val b1 = src.get().toInt() and 0xFF
                val b2 = src.get().toInt()           // keeps sign
                dst[i] = ((b2 shl 16) or (b1 shl 8) or b0) * SCALE_24
            }
            PcmFormat.S24_BE -> for (i in 0 until count) {
                val b2 = src.get().toInt()           // keeps sign
                val b1 = src.get().toInt() and 0xFF
                val b0 = src.get().toInt() and 0xFF
                dst[i] = ((b2 shl 16) or (b1 shl 8) or b0) * SCALE_24
            }
            PcmFormat.S32_LE, PcmFormat.S32_BE -> {
                val order = if (format == PcmFormat.S32_LE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val view = src.slice().order(order).asIntBuffer()
                for (i in 0 until count) dst[i] = (view.get(i) * SCALE_32).toFloat()
                src.position(src.position() + count * 4)
            }
            PcmFormat.F32 -> {
                val view = src.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
                view.get(dst, 0, count)
                src.position(src.position() + count * 4)
            }
        }
        return count
    }
}
