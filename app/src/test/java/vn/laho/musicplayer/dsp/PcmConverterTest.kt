// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmConverterTest {

    private fun convert(bytes: ByteArray, format: PcmFormat): FloatArray {
        val src = ByteBuffer.wrap(bytes)
        val dst = FloatArray(bytes.size / format.bytesPerSample)
        val n = PcmConverter.toFloat(src, format, dst)
        assertEquals(dst.size, n)
        assertEquals("position must advance to limit", bytes.size, src.position())
        return dst
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun s16LittleEndian() {
        // 0x8000 = -32768, 0x7FFF = 32767, 0x0001 = 1
        val out = convert(bytes(0x00, 0x80, 0xFF, 0x7F, 0x01, 0x00), PcmFormat.S16_LE)
        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768f, 1f / 32768f), out, 0f)
    }

    @Test
    fun s16BigEndian() {
        val out = convert(bytes(0x80, 0x00, 0x7F, 0xFF), PcmFormat.S16_BE)
        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768f), out, 0f)
    }

    @Test
    fun s24LittleEndianKeepsSignAndIsExact() {
        // 0x800000 = min, 0x7FFFFF = max, 0xFFFFFF = -1, 0x000001 = +1 LSB
        val out = convert(
            bytes(0x00, 0x00, 0x80, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0x01, 0x00, 0x00),
            PcmFormat.S24_LE,
        )
        val lsb = 1f / 8388608f
        assertArrayEquals(floatArrayOf(-1f, 1f - lsb, -lsb, lsb), out, 0f)
    }

    @Test
    fun s24BigEndian() {
        val out = convert(bytes(0x80, 0x00, 0x00, 0x00, 0x00, 0x01), PcmFormat.S24_BE)
        assertArrayEquals(floatArrayOf(-1f, 1f / 8388608f), out, 0f)
    }

    @Test
    fun s32LittleEndian() {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(Int.MIN_VALUE).putInt(1 shl 30)
        val out = convert(bb.array(), PcmFormat.S32_LE)
        assertArrayEquals(floatArrayOf(-1f, 0.5f), out, 0f)
    }

    @Test
    fun unsigned8Bit() {
        val out = convert(bytes(0x00, 0x80, 0xFF), PcmFormat.U8)
        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128f), out, 0f)
    }

    @Test
    fun floatPassesThroughUnchanged() {
        val values = floatArrayOf(0.1f, -0.75f, 1.0f)
        val bb = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        values.forEach { bb.putFloat(it) }
        assertArrayEquals(values, convert(bb.array(), PcmFormat.F32), 0f)
    }

    @Test
    fun respectsBufferPosition() {
        val src = ByteBuffer.wrap(bytes(0x7F, 0x00, 0x80, 0x00, 0x40)) // skip first byte
        src.position(1)
        val dst = FloatArray(2)
        assertEquals(2, PcmConverter.toFloat(src, PcmFormat.S16_LE, dst))
        assertArrayEquals(floatArrayOf(-1f, 0.5f), dst, 0f)
    }
}
