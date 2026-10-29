// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.laho.musicplayer.audio.AudioPathSnapshot
import vn.laho.musicplayer.audio.AudioPathSource
import vn.laho.musicplayer.signal.SignalPathInfo

class ExplainAudioPathToolTest {

    private fun run(q: PlaybackQualityInfo?, aspect: String? = null, granted: Boolean = true): ToolResult {
        val tool = ExplainAudioPathTool(AudioPathSource { q?.let { AudioPathSnapshot(it, granted) } })
        return runBlocking { tool.execute(if (aspect == null) emptyMap() else mapOf("aspect" to aspect)) }
    }

    private val usbBitPerfect = PlaybackQualityInfo(
        sourceCodec = "FLAC", sourceSampleRateHz = 96_000, sourceBitDepth = 24,
        decodedSampleRateHz = 96_000, outputDeviceName = "Tempotec Sonata BHD", isBitPerfect = true,
        lahoSignalPath = SignalPathInfo(
            mode = SignalPathInfo.Mode.USB_DRIVER, sampleRateHz = 96_000, sourceBitDepth = 24, outputBitDepth = 32,
            dacName = "Tempotec Sonata BHD", isBitPerfect = true,
        ),
    )

    private val ldac = PlaybackQualityInfo(
        sourceCodec = "FLAC", sourceSampleRateHz = 96_000, decodedSampleRateHz = 96_000, outputDeviceName = "WH-1000XM4",
        isBluetooth = true, bluetoothCodec = "LDAC", bluetoothBitrate = "990kbps",
        bluetoothSampleRateHz = 48_000, bluetoothBitDepth = 24, isResampled = true,
    )

    // ── bit-perfect ──

    @Test fun usbDriverBitPerfectIsYes() {
        val r = run(usbBitPerfect, "bit_perfect")
        assertTrue(r.success)
        assertTrue(r.message.startsWith("Có, đang bit-perfect"))
        assertTrue("96 kHz" in r.message && "Tempotec" in r.message)
        assertEquals(true, r.data["bitPerfect"])
        assertEquals(96_000, r.data["outputSampleRateHz"])
        assertEquals(32, r.data["outputBitDepth"])
        assertEquals(true, r.data["usbDriverActive"])
    }

    @Test fun usbFallbackIsNoWithReason() {
        val q = PlaybackQualityInfo(
            outputDeviceName = "Loa ngoài",
            lahoSignalPath = SignalPathInfo(
                mode = SignalPathInfo.Mode.USB_FALLBACK, fallbackRateHz = 192_000,
                fallbackReason = SignalPathInfo.FallbackReason.RATE_NOT_SUPPORTED,
            ),
        )
        val r = run(q, "bit_perfect")
        assertTrue(r.message.startsWith("Không"))
        assertTrue("192 kHz" in r.message && "DAC không hỗ trợ tần số này" in r.message)
        assertEquals(false, r.data["bitPerfect"])
        assertEquals("RATE_NOT_SUPPORTED", r.data["usbFallbackReason"])
    }

    @Test fun dspIsNotBitPerfectAndReportsPeq() {
        val q = PlaybackQualityInfo(
            decodedSampleRateHz = 48_000, outputDeviceName = "Tai nghe có dây",
            lahoSignalPath = SignalPathInfo(
                mode = SignalPathInfo.Mode.LAHO_DSP, outputBitDepth = 32, engine = SignalPathInfo.Engine.FLOAT_DSP,
                peqEnabled = true, peqBandCount = 5,
            ),
        )
        val r = run(q, "bit_perfect")
        assertTrue("PEQ 5 băng" in r.message)
        assertEquals(false, r.data["bitPerfect"])
        assertEquals(true, r.data["dspActive"])
        assertEquals(5, r.data["peqBands"])
    }

    @Test fun plainAndroidPathIsNotGuaranteed() {
        val r = run(PlaybackQualityInfo(outputDeviceName = "Loa", decodedSampleRateHz = 44_100), "bit_perfect")
        assertTrue(r.message.startsWith("Không xác nhận được bit-perfect"))
        assertEquals("NOT_GUARANTEED", r.data["verdict"])
    }

    @Test fun resampledAndroidPathSaysSo() {
        val r = run(
            PlaybackQualityInfo(sourceSampleRateHz = 96_000, decodedSampleRateHz = 48_000, isResampled = true),
            "bit_perfect",
        )
        assertTrue("96 kHz" in r.message && "48 kHz" in r.message)
        assertEquals("RESAMPLED", r.data["verdict"])
    }

    // ── Bluetooth ──

    @Test fun ldacIsNeverBitPerfectAndGetsTheHonestNote() {
        val r = run(ldac, "codec")
        assertTrue("LDAC" in r.message && "990 kbps" in r.message)
        assertTrue("không bit-perfect" in r.message.lowercase())
        assertTrue("bitrate thực tế tự hạ" in r.message)
        assertEquals("LDAC", r.data["bluetoothCodec"])
        assertEquals("LOSSY_HIGH_BITRATE", r.data["bluetoothWarning"])
        assertEquals(false, run(ldac, "bit_perfect").data["bitPerfect"])
    }

    @Test fun aacGetsLossyWarningWithTypicalBitrate() {
        val q = ldac.copy(bluetoothCodec = "AAC", bluetoothBitrate = null, bluetoothSampleRateHz = 44_100, bluetoothBitDepth = 16)
        val r = run(q, "codec")
        assertTrue("AAC" in r.message && "điển hình" in r.message)
        assertTrue("nén có mất dữ liệu / lấy mẫu lại" in r.message)
        assertEquals(true, r.data["bluetoothBitrateApproximate"])
    }

    @Test fun unreadableCodecDegradesWithoutFailing() {
        val q = ldac.copy(bluetoothCodec = "A2DP", bluetoothBitrate = null, bluetoothSampleRateHz = null, bluetoothBitDepth = null)
        val denied = run(q, "codec", granted = false)
        assertTrue(denied.success)
        assertTrue("codec không xác định" in denied.message)
        assertTrue("chưa cấp quyền" in denied.message)
        assertNull(denied.data["bluetoothCodec"])
        assertEquals(true, denied.data["bluetoothPermissionMissing"])
        val granted = run(q, "codec", granted = true)
        assertFalse("chưa cấp quyền" in granted.message)
        assertEquals(false, granted.data["bluetoothPermissionMissing"])
    }

    @Test fun bluetoothSampleRateQuestionMentionsResampling() {
        val r = run(ldac, "sample_rate")
        assertTrue("96 kHz" in r.message && "48 kHz" in r.message && "lấy mẫu lại" in r.message)
    }

    @Test fun codecQuestionOnNonBluetoothOutput() {
        val r = run(usbBitPerfect, "codec")
        assertTrue(r.message.startsWith("Đang không phát qua Bluetooth"))
        assertEquals(false, r.data["bluetooth"])
    }

    // ── general ──

    @Test fun sampleRateOnUsbIsTheStreamRate() {
        val r = run(usbBitPerfect, "sample_rate")
        assertTrue("96 kHz" in r.message && "32-bit" in r.message)
    }

    @Test fun defaultAspectAnswersBitPerfectAndRate() {
        val r = run(usbBitPerfect)
        assertTrue(r.message.startsWith("Có, đang bit-perfect") && "Đang phát ở 96 kHz" in r.message)
    }

    @Test fun failsWithoutData() {
        val r = run(null)
        assertFalse(r.success)
        assertTrue("phát một bài" in r.message)
    }

    @Test fun registeredInDefaultRegistry() {
        assertNotNull(ToolRegistry.default(FakePlayer())["explain_audio_path"])
    }

    // ── offline phrases ──

    private val parser = OfflineCommandParser()
    private fun aspectOf(text: String) = parser.parse(text)?.takeIf { it.name == "explain_audio_path" }?.args?.get("aspect")

    @Test fun offlineBitPerfectQuestions() {
        assertEquals("bit_perfect", aspectOf("tôi đang nghe bit-perfect không?"))
        assertEquals("bit_perfect", aspectOf("Có bit perfect không"))
        assertEquals("bit_perfect", aspectOf("đang phát bitperfect chưa"))
    }

    @Test fun offlineSampleRateQuestions() {
        assertEquals("sample_rate", aspectOf("đang phát ở tần số nào?"))
        assertEquals("sample_rate", aspectOf("tần số hiện tại là bao nhiêu"))
        assertEquals("sample_rate", aspectOf("sample rate bao nhiêu"))
    }

    @Test fun offlineCodecQuestions() {
        assertEquals("codec", aspectOf("tai nghe đang dùng codec gì?"))
        assertEquals("codec", aspectOf("bluetooth đang dùng LDAC hay AAC"))
    }

    @Test fun offlinePathQuestion() = assertEquals("all", aspectOf("cho xem đường tín hiệu"))

    @Test fun playCommandsAreNotHijacked() {
        assertEquals("search_and_play", parser.parse("phát bài Tần Số Yêu")?.name)
        assertEquals("playback_control", parser.parse("phát nhạc")?.name)
        assertEquals("get_now_playing", parser.parse("bài gì đây")?.name)
    }
}
