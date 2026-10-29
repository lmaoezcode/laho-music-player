// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.usb

import com.decent.usbaudio.SampleRateRange
import com.decent.usbaudio.Uac2Descriptors
import com.decent.usbaudio.UsbAltFormat
import com.decent.usbaudio.UsbPacketBudget
import com.decent.usbaudio.UsbRatePolicy
import com.decent.usbaudio.UsbRateRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic added to the vendored driver for hi-res (88.2–192 kHz) support. */
class UsbAudioCapabilitiesTest {

    // ── GET RANGE parsing ────────────────────────────────────────────

    private fun rangeBlock(vararg ranges: IntArray, declared: Int = ranges.size): ByteArray {
        val out = ByteArray(2 + 12 * ranges.size)
        out[0] = (declared and 0xFF).toByte()
        out[1] = (declared shr 8).toByte()
        ranges.forEachIndexed { i, r ->
            for (j in 0 until 3) {
                val v = r[j]
                val o = 2 + i * 12 + j * 4
                for (b in 0 until 4) out[o + b] = (v shr (8 * b)).toByte()
            }
        }
        return out
    }

    @Test
    fun parsesDiscreteRateList() {
        val rates = intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
        val data = rangeBlock(*rates.map { intArrayOf(it, it, 0) }.toTypedArray())
        val ranges = Uac2Descriptors.parseSampleRateRanges(data)!!
        assertEquals(8, ranges.size)
        assertEquals(SampleRateRange(176_400, 176_400, 0), ranges[4])
        assertEquals(rates.toList(), UsbRatePolicy.supportedRates(ranges))
    }

    @Test
    fun continuousRangeHonoursResolution() {
        val r = SampleRateRange(8_000, 192_000, 100)
        assertTrue(r.contains(44_100))
        assertTrue(r.contains(192_000))
        assertFalse(r.contains(44_150))
        assertFalse(r.contains(384_000))
        // res 0 on a span: treated as continuous
        assertTrue(SampleRateRange(44_100, 96_000, 0).contains(88_200))
        // discrete
        assertFalse(SampleRateRange(48_000, 48_000, 0).contains(44_100))
    }

    @Test
    fun truncatedReplyKeepsCompleteSubRanges() {
        val full = rangeBlock(intArrayOf(44_100, 44_100, 0), intArrayOf(48_000, 48_000, 0))
        val ranges = Uac2Descriptors.parseSampleRateRanges(full, length = 2 + 12 + 5)!!
        assertEquals(listOf(SampleRateRange(44_100, 44_100, 0)), ranges)
    }

    @Test
    fun malformedRepliesAreNull() {
        assertNull(Uac2Descriptors.parseSampleRateRanges(ByteArray(0)))
        assertNull(Uac2Descriptors.parseSampleRateRanges(byteArrayOf(0, 0)))          // 0 sub-ranges
        assertNull(Uac2Descriptors.parseSampleRateRanges(byteArrayOf(2, 0, 1, 2, 3))) // count but no body
        assertNull(Uac2Descriptors.parseSampleRateRanges(ByteArray(14), length = -1)) // failed transfer
        assertEquals(2 + 12 * 3, Uac2Descriptors.rangeReplyLength(3))
    }

    // ── Descriptor walk ──────────────────────────────────────────────

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesAltFormatsAndIsoOutEndpoints() {
        val raw = bytes(
            9, 4, 0, 0, 0, 1, 1, 0x20, 0,                 // AudioControl
            9, 4, 1, 0, 0, 1, 2, 0x20, 0,                 // AudioStreaming alt 0
            9, 4, 1, 1, 2, 1, 2, 0x20, 0,                 // alt 1
            16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0, // AS_GENERAL (ignored)
            6, 0x24, 2, 1, 4, 24,                         // FORMAT_TYPE I: 4-byte slot, 24 bits
            7, 5, 0x01, 0x05, 0x88, 0x01, 2,              // ISO OUT 392 B, bInterval 2
            8, 0x25, 1, 0, 0, 0, 0, 0,                    // CS endpoint
            7, 5, 0x81, 0x11, 4, 0, 4,                    // ISO IN feedback (ignored)
            9, 4, 1, 2, 2, 1, 2, 0x20, 0,                 // alt 2
            6, 0x24, 2, 1, 4, 32,
            7, 5, 0x01, 0x05, 0x00, 0x04, 1,              // ISO OUT 1024 B, bInterval 1
            9, 4, 2, 0, 1, 3, 0, 0, 0,                    // HID interface after it
            7, 5, 0x02, 0x03, 8, 0, 10,                   // interrupt OUT (not streaming)
        )
        assertEquals(
            listOf(UsbAltFormat(1, 4, 24, 392, 2), UsbAltFormat(2, 4, 32, 1024, 1)),
            Uac2Descriptors.parseStreamingAltFormats(raw)
        )
    }

    @Test
    fun truncatedDescriptorsDoNotCrash() {
        val raw = bytes(9, 4, 1, 1, 2, 1, 2, 0x20, 0, 6, 0x24, 2, 1, 4) // format cut short
        assertEquals(emptyList<UsbAltFormat>(), Uac2Descriptors.parseStreamingAltFormats(raw))
    }

    // ── Packet budget: fpmf × 2^(bInterval-1) × bytesPerFrame ≤ wMaxPacketSize ──

    @Test
    fun framesPerPacketFollowsInterval() {
        assertEquals(1, UsbPacketBudget.microframesPerPacket(1))
        assertEquals(2, UsbPacketBudget.microframesPerPacket(2))
        assertEquals(8, UsbPacketBudget.microframesPerPacket(4))
        assertEquals(1, UsbPacketBudget.microframesPerPacket(0))
        assertEquals(12, UsbPacketBudget.maxFramesPerPacket(44_100, 2))   // 11.025 → 11 (+1)
        assertEquals(13, UsbPacketBudget.maxFramesPerPacket(48_000, 2))   // 12 exactly (+1)
        assertEquals(45, UsbPacketBudget.maxFramesPerPacket(176_400, 2))
        assertEquals(49, UsbPacketBudget.maxFramesPerPacket(192_000, 2))
        assertEquals(25, UsbPacketBudget.maxFramesPerPacket(192_000, 1))
        assertEquals(392, UsbPacketBudget.maxBytesPerPacket(192_000, 2, 8))
    }

    @Test
    fun effectiveMaxPacketIncludesHighBandwidthTransactions() {
        assertEquals(1024, UsbPacketBudget.effectiveMaxPacket(0x0400))
        assertEquals(2048, UsbPacketBudget.effectiveMaxPacket(0x0C00))
        assertEquals(3072, UsbPacketBudget.effectiveMaxPacket(0x1400))
    }

    // ── Rate decision ────────────────────────────────────────────────

    private val sonataLike = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
        .map { SampleRateRange(it, it, 0) }

    @Test
    fun allHiResRatesFitTypicalHighSpeedDac() {
        for (rate in intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
            assertNull(
                "rate $rate",
                UsbRatePolicy.check(rate, 2, 4, interval = 2, rawMaxPacketSize = 1024, ranges = sonataLike)
            )
        }
    }

    @Test
    fun rejectsRateMissingFromRange() {
        val upTo96 = listOf(SampleRateRange(44_100, 44_100, 0), SampleRateRange(48_000, 96_000, 48_000))
        assertNull(UsbRatePolicy.check(96_000, 2, 4, 1, 1024, upTo96))
        assertEquals(
            UsbRateRejection.RATE_NOT_SUPPORTED,
            UsbRatePolicy.check(88_200, 2, 4, 1, 1024, upTo96)
        )
        assertEquals(
            UsbRateRejection.RATE_NOT_SUPPORTED,
            UsbRatePolicy.check(192_000, 2, 4, 1, 1024, upTo96)
        )
    }

    @Test
    fun unknownRangesAreAttemptedAndVerifiedLater() {
        assertNull(UsbRatePolicy.check(192_000, 2, 4, 1, 1024, ranges = null))
        assertNull(UsbRatePolicy.check(192_000, 2, 4, 1, 1024, ranges = emptyList()))
        assertEquals(emptyList<Int>(), UsbRatePolicy.supportedRates(null))
    }

    @Test
    fun rejectsPacketLargerThanEndpoint() {
        // 192 kHz, 32-bit stereo, bInterval 2 → 49 frames × 8 B = 392 B
        assertNull(UsbRatePolicy.check(192_000, 2, 4, 2, rawMaxPacketSize = 392, ranges = sonataLike))
        assertEquals(
            UsbRateRejection.PACKET_TOO_LARGE,
            UsbRatePolicy.check(192_000, 2, 4, 2, rawMaxPacketSize = 391, ranges = sonataLike)
        )
        // A DAC whose alt setting is only sized for 96 kHz
        assertEquals(
            UsbRateRejection.PACKET_TOO_LARGE,
            UsbRatePolicy.check(176_400, 2, 4, 2, rawMaxPacketSize = 200, ranges = null)
        )
        // Unknown wMaxPacketSize: only the native URB buffer limits
        assertNull(UsbRatePolicy.check(192_000, 2, 4, 2, rawMaxPacketSize = 0, ranges = null))
    }

    @Test
    fun rejectsUrbBufferOverflow() {
        // 384 kHz, 32-bit stereo, bInterval 3 (4 microframes): 193 frames × 8 B = 1544 B/packet;
        // the endpoint allows it (2 transactions) but 8 packets overflow the 8 KiB URB buffer.
        assertEquals(
            UsbRateRejection.URB_BUFFER_TOO_SMALL,
            UsbRatePolicy.check(384_000, 2, 4, 3, rawMaxPacketSize = 0x0C00, ranges = sonataLike)
        )
    }

    @Test
    fun readBackMustMatchWhenAnswered() {
        assertNull(UsbRatePolicy.verifyReadBack(96_000, 96_000))
        assertNull(UsbRatePolicy.verifyReadBack(96_000, -1))  // DAC does not answer GET_CUR
        assertEquals(UsbRateRejection.RATE_NOT_APPLIED, UsbRatePolicy.verifyReadBack(192_000, 48_000))
    }
}
