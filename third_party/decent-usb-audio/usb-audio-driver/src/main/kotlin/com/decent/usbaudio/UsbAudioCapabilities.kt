// LaHo addition to the vendored decent-player driver (see ../../../../../../../README.md,
// "LaHo modifications"). MIT, like the rest of this module.
//
// Pure logic only (no Android APIs) so it is unit-tested from the app module
// (app/src/test/java/vn/laho/musicplayer/usb/).
package com.decent.usbaudio

import kotlin.math.floor

/**
 * One UAC2 RANGE sub-range of the clock source's CS_SAM_FREQ_CONTROL (UAC2 §5.2.3.3):
 * `dMIN`, `dMAX`, `dRES`. A discrete rate has `min == max` (and `res == 0`).
 */
data class SampleRateRange(val minHz: Int, val maxHz: Int, val resHz: Int) {
    fun contains(rateHz: Int): Boolean {
        if (rateHz < minHz || rateHz > maxHz) return false
        if (minHz == maxHz) return rateHz == minHz
        // res == 0 on a min..max span: treat as continuous (some DACs report this).
        if (resHz <= 0) return true
        return (rateHz - minHz) % resHz == 0
    }
}

/** Format of one non-zero alternate setting of the AudioStreaming interface. */
data class UsbAltFormat(
    val altSetting: Int,
    /** bSubslotSize: bytes per sample on the wire. */
    val subslotBytes: Int,
    /** bBitResolution: significant bits per sample. */
    val bitResolution: Int,
    /** Raw wMaxPacketSize of the ISO OUT data endpoint (0 if not found). */
    val rawMaxPacketSize: Int,
    /** bInterval of the ISO OUT data endpoint. */
    val interval: Int,
) {
    /** Bytes per (micro)frame service opportunity: size × (1 + additional transactions). */
    val maxPacketBytes: Int get() = UsbPacketBudget.effectiveMaxPacket(rawMaxPacketSize)
}

object Uac2Descriptors {

    /**
     * Parses the parameter block of a UAC2 GET RANGE request on a 4-byte control:
     * `wNumSubRanges` (LE16) followed by `wNumSubRanges` × (dMIN, dMAX, dRES) (LE32 each).
     *
     * @param length number of valid bytes in [data] (the control transfer result).
     * @return the sub-ranges, or null if the block is malformed/truncated or empty.
     */
    fun parseSampleRateRanges(data: ByteArray, length: Int = data.size): List<SampleRateRange>? {
        val n = minOf(length, data.size)
        if (n < 2) return null
        val count = le16(data, 0)
        if (count <= 0) return null
        val available = (n - 2) / 12
        if (available <= 0) return null
        // Accept a truncated reply as long as at least one complete sub-range came back.
        val parsed = minOf(count, available)
        return List(parsed) { i ->
            val o = 2 + i * 12
            SampleRateRange(le32(data, o), le32(data, o + 4), le32(data, o + 8))
        }
    }

    /** Number of bytes a GET RANGE reply with [subRanges] sub-ranges occupies. */
    fun rangeReplyLength(subRanges: Int): Int = 2 + 12 * subRanges

    /**
     * Walks raw configuration descriptors and returns the format of every non-zero alt
     * setting of the (first) AudioStreaming interface: Format Type I (bSubslotSize,
     * bBitResolution) and the ISO OUT endpoint (wMaxPacketSize, bInterval).
     */
    fun parseStreamingAltFormats(raw: ByteArray): List<UsbAltFormat> {
        val out = mutableListOf<UsbAltFormat>()
        var i = 0
        var inStreaming = false
        var streamingIface = -1
        var alt = -1
        var subslot = 0
        var bits = 0
        var maxPacket = 0
        var interval = 1
        var haveFormat = false

        fun flush() {
            if (inStreaming && alt > 0 && haveFormat) {
                out += UsbAltFormat(alt, subslot, bits, maxPacket, interval)
            }
            haveFormat = false
            subslot = 0; bits = 0; maxPacket = 0; interval = 1
        }

        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            when (raw[i + 1].toInt() and 0xFF) {
                0x04 -> if (len >= 9) { // INTERFACE
                    flush()
                    val num = raw[i + 2].toInt() and 0xFF
                    val cls = raw[i + 5].toInt() and 0xFF
                    val sub = raw[i + 6].toInt() and 0xFF
                    val isStreaming = cls == 1 && sub == 2
                    if (isStreaming && streamingIface < 0) streamingIface = num
                    inStreaming = isStreaming && num == streamingIface
                    alt = raw[i + 3].toInt() and 0xFF
                }
                0x24 -> if (inStreaming && len >= 6 && (raw[i + 2].toInt() and 0xFF) == 0x02) {
                    subslot = raw[i + 4].toInt() and 0xFF   // FORMAT_TYPE (I)
                    bits = raw[i + 5].toInt() and 0xFF
                    haveFormat = true
                }
                0x05 -> if (inStreaming && len >= 7) {      // ENDPOINT
                    val addr = raw[i + 2].toInt() and 0xFF
                    val attrs = raw[i + 3].toInt() and 0xFF
                    val isIsoOut = (addr and 0x80) == 0 && (attrs and 0x03) == 0x01
                    if (isIsoOut) {
                        maxPacket = le16(raw, i + 4)
                        interval = raw[i + 6].toInt() and 0xFF
                    }
                }
            }
            i += len
        }
        flush()
        return out
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

/**
 * Isochronous packet budget, mirroring the native packetizer in usb-audio-output.cpp:
 * each packet carries `fpmf × 2^(bInterval-1)` frames (fpmf = rate / 8000 on high speed),
 * the fractional part accumulating, so a packet holds at most `floor(nominal) + 1` frames
 * (the +1 also covers an asynchronous feedback endpoint running slightly fast).
 */
object UsbPacketBudget {
    /** Must match USB_AUDIO_PACKETS_PER_URB in usb-audio-output.h. */
    const val PACKETS_PER_URB = 8
    /** Must match USB_AUDIO_URB_BUFFER_SIZE in usb-audio-output.h. */
    const val URB_BUFFER_BYTES = 8192

    fun microframesPerPacket(interval: Int): Int =
        if (interval in 1..4) 1 shl (interval - 1) else 1

    fun maxFramesPerPacket(sampleRateHz: Int, interval: Int): Int =
        floor(sampleRateHz / 8000.0 * microframesPerPacket(interval)).toInt() + 1

    fun maxBytesPerPacket(sampleRateHz: Int, interval: Int, bytesPerFrame: Int): Int =
        maxFramesPerPacket(sampleRateHz, interval) * bytesPerFrame

    /** wMaxPacketSize bits 10..0 = size, bits 12..11 = additional transactions per microframe. */
    fun effectiveMaxPacket(rawMaxPacketSize: Int): Int {
        val size = rawMaxPacketSize and 0x7FF
        val extra = (rawMaxPacketSize shr 11) and 0x3
        return size * (1 + minOf(extra, 2))
    }
}

/** Why a sample rate cannot be streamed bit-perfect to the DAC. */
enum class UsbRateRejection {
    /** The clock source's GET RANGE does not list the rate. */
    RATE_NOT_SUPPORTED,
    /** One packet at this rate would exceed the endpoint's wMaxPacketSize. */
    PACKET_TOO_LARGE,
    /** One URB at this rate would overflow the native URB buffer. */
    URB_BUFFER_TOO_SMALL,
    /** SET_CUR was accepted but GET_CUR reports a different rate. */
    RATE_NOT_APPLIED,
    /** The device could not be opened or the stream could not start. */
    STREAM_FAILED,
}

object UsbRatePolicy {

    /**
     * Decides whether [sampleRateHz] can be streamed before touching the device.
     *
     * @param ranges the clock source's GET RANGE result; null when the DAC did not answer
     *   (then the rate is attempted and verified with GET_CUR afterwards).
     * @param bytesPerSample bytes the native layer sends per sample (bitDepth / 8).
     * @param rawMaxPacketSize wMaxPacketSize of the alt setting that will stream; <= 0 if unknown.
     * @return null if the rate can be used, otherwise the reason it cannot.
     */
    fun check(
        sampleRateHz: Int,
        channelCount: Int,
        bytesPerSample: Int,
        interval: Int,
        rawMaxPacketSize: Int,
        ranges: List<SampleRateRange>?,
    ): UsbRateRejection? {
        if (ranges != null && ranges.isNotEmpty() && ranges.none { it.contains(sampleRateHz) }) {
            return UsbRateRejection.RATE_NOT_SUPPORTED
        }
        val packetBytes = UsbPacketBudget.maxBytesPerPacket(
            sampleRateHz, interval, bytesPerSample * channelCount
        )
        if (rawMaxPacketSize > 0 && packetBytes > UsbPacketBudget.effectiveMaxPacket(rawMaxPacketSize)) {
            return UsbRateRejection.PACKET_TOO_LARGE
        }
        if (packetBytes * UsbPacketBudget.PACKETS_PER_URB > UsbPacketBudget.URB_BUFFER_BYTES) {
            return UsbRateRejection.URB_BUFFER_TOO_SMALL
        }
        return null
    }

    /**
     * Result of the GET_CUR read-back after SET_CUR. A DAC that does not answer GET_CUR
     * (readBackHz <= 0) is trusted; a different non-zero rate means the clock did not switch.
     */
    fun verifyReadBack(requestedHz: Int, readBackHz: Int): UsbRateRejection? =
        if (readBackHz > 0 && readBackHz != requestedHz) UsbRateRejection.RATE_NOT_APPLIED else null

    /** All rates (from [candidates]) that the ranges allow; used for logs and the UI. */
    fun supportedRates(
        ranges: List<SampleRateRange>?,
        candidates: IntArray = COMMON_RATES,
    ): List<Int> = if (ranges.isNullOrEmpty()) emptyList()
        else candidates.filter { r -> ranges.any { it.contains(r) } }

    val COMMON_RATES = intArrayOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000
    )
}
