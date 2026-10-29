// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioPathTest {

    private fun path(name: String?, quality: String? = null, sr: Int? = 44_100, bits: Int? = 16, granted: Boolean = true) =
        BluetoothAudioPath.from(name, quality, sr, bits, granted)

    // ── codec name mapping ──

    @Test fun mapsKnownNames() {
        assertEquals(BluetoothCodec.SBC, BluetoothAudioPath.codecOf("SBC"))
        assertEquals(BluetoothCodec.AAC, BluetoothAudioPath.codecOf("aac"))
        assertEquals(BluetoothCodec.APTX, BluetoothAudioPath.codecOf("aptX"))
        assertEquals(BluetoothCodec.APTX_HD, BluetoothAudioPath.codecOf("aptX HD"))
        assertEquals(BluetoothCodec.APTX_HD, BluetoothAudioPath.codecOf("APTX-HD"))
        assertEquals(BluetoothCodec.APTX_ADAPTIVE, BluetoothAudioPath.codecOf("aptX Adaptive"))
        assertEquals(BluetoothCodec.LDAC, BluetoothAudioPath.codecOf("LDAC"))
        assertEquals(BluetoothCodec.LC3, BluetoothAudioPath.codecOf("LC3"))
        assertEquals(BluetoothCodec.OPUS, BluetoothAudioPath.codecOf("OPUS"))
        assertEquals(BluetoothCodec.LHDC, BluetoothAudioPath.codecOf("LHDC V3"))
    }

    @Test fun unreadableNamesAreUnknown() {
        for (n in listOf(null, "", "  ", "A2DP", "INVALID CODEC", "UNKNOWN CODEC(12)")) {
            assertEquals("'$n'", BluetoothCodec.UNKNOWN, BluetoothAudioPath.codecOf(n))
        }
    }

    @Test fun unnamedCodecKeepsReportedName() {
        val p = path("FooCodec 9")
        assertEquals(BluetoothCodec.OTHER, p.codec)
        assertEquals("FooCodec 9", p.codecDisplayName)
        assertTrue(p.isCodecKnown)
    }

    // ── bitrate ──

    @Test fun ldacBitrateIsReadFromQuality() {
        val p = path("LDAC", "990kbps", 96_000, 24)
        assertEquals(990, p.bitrateKbps)
        assertFalse(p.bitrateApproximate)
    }

    @Test fun ldacAutoHasNoBitrate() {
        val p = path("LDAC", "Auto")
        assertNull(p.bitrateKbps)
        assertFalse(p.bitrateApproximate)
    }

    @Test fun sbcAndAacFallBackToTypicalBitrate() {
        assertEquals(328, path("SBC").bitrateKbps)
        assertTrue(path("SBC").bitrateApproximate)
        assertEquals(256, path("AAC").bitrateKbps)
        assertEquals(576, path("aptX HD").bitrateKbps)
    }

    @Test fun unknownCodecHasNoBitrate() = assertNull(path("A2DP").bitrateKbps)

    @Test fun zeroRateAndBitsBecomeNull() {
        val p = path("AAC", sr = 0, bits = 0)
        assertNull(p.sampleRateHz)
        assertNull(p.bitsPerSample)
    }

    // ── warning rules ──

    @Test fun lossyCodecsWarnLossy() {
        for (n in listOf("SBC", "AAC", "aptX", "LC3", "OPUS")) assertEquals(n, BluetoothWarning.LOSSY, path(n).warning)
    }

    @Test fun highBitrateCodecsGetTheHonestNote() {
        for (n in listOf("LDAC", "aptX HD", "aptX Adaptive", "LHDC V5")) {
            assertEquals(n, BluetoothWarning.LOSSY_HIGH_BITRATE, path(n).warning)
        }
    }

    @Test fun unknownCodecWarnsUnknown() = assertEquals(BluetoothWarning.UNKNOWN_CODEC, path(null).warning)

    @Test fun bluetoothIsNeverBitPerfect() {
        for (n in listOf("SBC", "LDAC", "aptX HD", "A2DP", null)) assertFalse(path(n).isBitPerfect)
    }

    // ── permission fallback ──

    @Test fun promptsOnlyWhenCodecUnreadableAndPermissionMissing() {
        assertTrue(path("A2DP", granted = false).needsPermissionPrompt)
        assertFalse(path("A2DP", granted = true).needsPermissionPrompt)  // system refuses: nothing to ask
        assertFalse(path("LDAC", granted = false).needsPermissionPrompt) // already read
    }

    // ── from PlaybackQualityInfo ──

    @Test fun nullWhenOutputIsNotBluetooth() = assertNull(PlaybackQualityInfo().bluetoothAudioPath(true))

    @Test fun readsTheBluetoothFieldsOfPlaybackQuality() {
        val q = PlaybackQualityInfo(
            isBluetooth = true, bluetoothCodec = "LDAC", bluetoothBitrate = "660kbps",
            bluetoothSampleRateHz = 96_000, bluetoothBitDepth = 24,
        )
        val p = q.bluetoothAudioPath(true)!!
        assertEquals(BluetoothCodec.LDAC, p.codec)
        assertEquals(660, p.bitrateKbps)
        assertEquals(96_000, p.sampleRateHz)
        assertEquals(24, p.bitsPerSample)
    }

    @Test fun serviceFallbackNameA2dpIsUnknown() {
        val q = PlaybackQualityInfo(isBluetooth = true, bluetoothCodec = "A2DP")
        assertEquals(BluetoothCodec.UNKNOWN, q.bluetoothAudioPath(false)!!.codec)
    }
}
