// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant

import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import vn.laho.musicplayer.audio.AudioPathSnapshot
import vn.laho.musicplayer.audio.AudioPathSource
import vn.laho.musicplayer.audio.BluetoothAudioPath
import vn.laho.musicplayer.audio.BluetoothCodec
import vn.laho.musicplayer.audio.BluetoothWarning
import vn.laho.musicplayer.audio.bluetoothAudioPath
import vn.laho.musicplayer.signal.SignalPathInfo

/** Why the output is, or is not, bit-perfect. Derived only from the measured signal path. */
enum class BitPerfectVerdict { YES, USB_NOT_BIT_PERFECT, USB_FALLBACK, BLUETOOTH, DSP, RESAMPLED, NOT_GUARANTEED }

/**
 * Facts about the current output path and the Vietnamese sentences built from them. Pure: the
 * same [PlaybackQualityInfo] the signal-path sheet shows goes in, nothing is guessed.
 */
object AudioPathReport {

    fun verdict(q: PlaybackQualityInfo): BitPerfectVerdict {
        val path = q.lahoSignalPath
        return when {
            path?.mode == SignalPathInfo.Mode.USB_DRIVER ->
                if (path.isBitPerfect) BitPerfectVerdict.YES else BitPerfectVerdict.USB_NOT_BIT_PERFECT
            path?.mode == SignalPathInfo.Mode.USB_FALLBACK -> BitPerfectVerdict.USB_FALLBACK
            q.isBluetooth -> BitPerfectVerdict.BLUETOOTH
            path?.mode == SignalPathInfo.Mode.LAHO_DSP -> BitPerfectVerdict.DSP
            q.isBitPerfect -> BitPerfectVerdict.YES
            q.isResampled -> BitPerfectVerdict.RESAMPLED
            else -> BitPerfectVerdict.NOT_GUARANTEED
        }
    }

    /** Rate on the last hop towards the device, 0 when unknown. */
    fun outputRateHz(q: PlaybackQualityInfo): Int {
        val path = q.lahoSignalPath
        return when {
            path?.mode == SignalPathInfo.Mode.USB_DRIVER -> path.sampleRateHz
            q.isBluetooth -> q.bluetoothSampleRateHz ?: 0
            else -> q.decodedSampleRateHz
        }
    }

    fun outputBitDepth(q: PlaybackQualityInfo): Int {
        val path = q.lahoSignalPath
        return when {
            path?.mode == SignalPathInfo.Mode.USB_DRIVER -> path.outputBitDepth
            q.isBluetooth -> q.bluetoothBitDepth ?: 0
            path?.mode == SignalPathInfo.Mode.LAHO_DSP -> path.outputBitDepth
            else -> q.decodedBitDepth
        }
    }

    fun facts(s: AudioPathSnapshot): Map<String, Any?> {
        val q = s.quality
        val path = q.lahoSignalPath
        val bt = q.bluetoothAudioPath(s.bluetoothPermissionGranted)
        val verdict = verdict(q)
        return buildMap {
            put("bitPerfect", verdict == BitPerfectVerdict.YES)
            put("verdict", verdict.name)
            put("outputDevice", q.outputDeviceName)
            put("sourceCodec", q.sourceCodec)
            put("sourceSampleRateHz", q.sourceSampleRateHz)
            put("sourceBitDepth", q.sourceBitDepth)
            put("outputSampleRateHz", outputRateHz(q))
            put("outputBitDepth", outputBitDepth(q))
            put("resampled", q.isResampled)
            put("usbDriverActive", path?.mode == SignalPathInfo.Mode.USB_DRIVER)
            put("dspActive", path?.mode == SignalPathInfo.Mode.LAHO_DSP)
            if (path != null && path.mode == SignalPathInfo.Mode.LAHO_DSP) {
                put("peqBands", if (path.peqEnabled) path.peqBandCount else 0)
            }
            if (path != null && path.mode != SignalPathInfo.Mode.LAHO_DSP) {
                put("dacName", path.dacName)
                if (path.dacReportedRateHz > 0) put("dacReportedRateHz", path.dacReportedRateHz)
            }
            if (path?.fallbackReason != null) put("usbFallbackReason", path.fallbackReason.name)
            put("bluetooth", bt != null)
            if (bt != null) {
                put("bluetoothCodec", if (bt.isCodecKnown) bt.codecDisplayName else null)
                put("bluetoothBitrateKbps", bt.bitrateKbps)
                put("bluetoothBitrateApproximate", bt.bitrateApproximate)
                put("bluetoothSampleRateHz", bt.sampleRateHz)
                put("bluetoothBitDepth", bt.bitsPerSample)
                put("bluetoothWarning", bt.warning.name)
                put("bluetoothPermissionMissing", bt.needsPermissionPrompt)
            }
        }
    }

    fun bitPerfectMessage(s: AudioPathSnapshot): String {
        val q = s.quality
        val path = q.lahoSignalPath
        val out = rate(outputRateHz(q))
        return when (verdict(q)) {
            BitPerfectVerdict.YES -> if (path?.mode == SignalPathInfo.Mode.USB_DRIVER) {
                "Có, đang bit-perfect: phát thẳng $out tới ${path.dacName.ifBlank { "USB DAC" }} bằng driver USB LaHo, " +
                    "không resample, không DSP."
            } else {
                "Có, đang bit-perfect (${q.outputDeviceName.ifBlank { "thiết bị hiện tại" }}), không resample."
            }
            BitPerfectVerdict.USB_NOT_BIT_PERFECT ->
                "Chưa. Driver USB LaHo đang chạy ($out) nhưng chưa đạt đủ điều kiện bit-perfect."
            BitPerfectVerdict.USB_FALLBACK ->
                "Không. Bài ${rate(path?.fallbackRateHz ?: 0)} không dùng được driver USB " +
                    "(${fallbackText(path?.fallbackReason)}), nên đang phát qua pipeline Android."
            BitPerfectVerdict.BLUETOOTH ->
                "Không. " + bluetoothSentence(q.bluetoothAudioPath(s.bluetoothPermissionGranted)) +
                    " Muốn bit-perfect hãy dùng USB DAC với driver USB LaHo."
            BitPerfectVerdict.DSP ->
                "Không. DSP LaHo (float 32-bit" +
                    (if (path != null && path.peqEnabled && path.peqBandCount > 0) ", PEQ ${path.peqBandCount} băng" else "") +
                    ") đang xử lý tín hiệu qua bộ trộn Android."
            BitPerfectVerdict.RESAMPLED ->
                "Không. Tín hiệu bị lấy mẫu lại từ ${rate(q.sourceSampleRateHz)} thành ${rate(q.decodedSampleRateHz)}."
            BitPerfectVerdict.NOT_GUARANTEED ->
                "Không xác nhận được bit-perfect: đang phát qua bộ trộn Android " +
                    "(${q.outputDeviceName.ifBlank { "thiết bị hiện tại" }}). Dùng USB DAC với driver USB LaHo để bit-perfect."
        }
    }

    fun sampleRateMessage(s: AudioPathSnapshot): String {
        val q = s.quality
        val src = rate(q.sourceSampleRateHz)
        val out = rate(outputRateHz(q))
        val bits = outputBitDepth(q).takeIf { it > 0 }?.let { ", $it-bit" }.orEmpty()
        val device = q.outputDeviceName.ifBlank { "thiết bị hiện tại" }
        return when {
            outputRateHz(q) <= 0 -> "Bài gốc $src; chưa đọc được tần số đầu ra tới $device."
            q.isBluetooth && q.sourceSampleRateHz > 0 && q.sourceSampleRateHz != outputRateHz(q) ->
                "Bài gốc $src, nhưng liên kết Bluetooth chạy ở $out$bits nên bị lấy mẫu lại."
            q.sourceSampleRateHz > 0 && q.sourceSampleRateHz != outputRateHz(q) ->
                "Bài gốc $src, đầu ra tới $device ở $out$bits (đã lấy mẫu lại)."
            else -> "Đang phát ở $out$bits tới $device."
        }
    }

    fun codecMessage(s: AudioPathSnapshot): String {
        val q = s.quality
        val bt = q.bluetoothAudioPath(s.bluetoothPermissionGranted)
            ?: return "Đang không phát qua Bluetooth (thiết bị: ${q.outputDeviceName.ifBlank { q.outputDeviceType }})."
        return bluetoothSentence(bt)
    }

    fun message(s: AudioPathSnapshot, aspect: String?): String = when (aspect) {
        "bit_perfect" -> bitPerfectMessage(s)
        "sample_rate" -> sampleRateMessage(s)
        "codec" -> codecMessage(s)
        else -> "${bitPerfectMessage(s)} ${sampleRateMessage(s)}"
    }

    /** One honest sentence about the Bluetooth leg, including the warning that matches the codec. */
    fun bluetoothSentence(bt: BluetoothAudioPath?): String {
        if (bt == null) return "Đang phát qua Bluetooth."
        val detail = buildList {
            bt.bitrateKbps?.let { add(if (bt.bitrateApproximate) "khoảng $it kbps (điển hình)" else "$it kbps") }
            bt.sampleRateHz?.let { add(rate(it)) }
            bt.bitsPerSample?.let { add("$it-bit") }
        }.joinToString(", ")
        val head = if (bt.isCodecKnown) "Bluetooth dùng codec ${bt.codecDisplayName}" else "Bluetooth (codec không xác định)"
        val tail = if (detail.isNotEmpty()) " ($detail)" else ""
        val warning = when (bt.warning) {
            BluetoothWarning.UNKNOWN_CODEC ->
                "Android không cho ứng dụng đọc codec" +
                    (if (bt.needsPermissionPrompt) " (chưa cấp quyền Thiết bị lân cận)" else " trên máy này") +
                    "; dù codec nào cũng không bit-perfect (nén có mất dữ liệu / lấy mẫu lại)."
            BluetoothWarning.LOSSY ->
                "Không bit-perfect (nén có mất dữ liệu / lấy mẫu lại)."
            BluetoothWarning.LOSSY_HIGH_BITRATE ->
                "Bitrate cao nên gần bản gốc hơn, nhưng vẫn không bit-perfect (nén có mất dữ liệu" +
                    (if (bt.codec == BluetoothCodec.LDAC) ", bitrate thực tế tự hạ khi sóng yếu" else "") + ")."
        }
        return "$head$tail. $warning"
    }

    private fun rate(hz: Int) = if (hz > 0) PlaybackQualityInfo.formatSampleRate(hz) else "không rõ tần số"

    private fun fallbackText(r: SignalPathInfo.FallbackReason?) = when (r) {
        SignalPathInfo.FallbackReason.RATE_NOT_SUPPORTED -> "DAC không hỗ trợ tần số này"
        SignalPathInfo.FallbackReason.PACKET_TOO_LARGE -> "gói USB quá lớn"
        SignalPathInfo.FallbackReason.URB_BUFFER_TOO_SMALL -> "bộ đệm USB quá nhỏ"
        SignalPathInfo.FallbackReason.RATE_NOT_APPLIED -> "DAC không chuyển sang tần số này"
        SignalPathInfo.FallbackReason.STREAM_FAILED -> "luồng USB lỗi"
        null -> "không rõ lý do"
    }
}

/**
 * Answers "tôi đang nghe bit-perfect không?", "đang phát ở tần số nào?", "tai nghe dùng codec gì?"
 * from the measured signal path ([AudioPathSource]); the reply is built from facts only.
 */
class ExplainAudioPathTool(private val source: AudioPathSource) : AssistantTool {
    override val name = "explain_audio_path"
    override val description =
        "Giải thích đường tín hiệu âm thanh đang dùng: có bit-perfect không, tần số/độ sâu bit đầu ra, " +
            "driver USB, DSP/PEQ, codec Bluetooth. Trả lời từ dữ liệu đo thật; hãy trích 'message' và 'data', không đoán."
    override val parameters = listOf(
        ToolParameter(
            "aspect", ToolParameter.Type.STRING,
            "bit_perfect: có bit-perfect không; sample_rate: tần số đầu ra; codec: codec Bluetooth; all: tất cả",
            enumValues = listOf("bit_perfect", "sample_rate", "codec", "all"),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val snapshot = source.current()
            ?: return ToolResult.fail("Chưa có dữ liệu đường tín hiệu. Hãy phát một bài rồi hỏi lại.")
        val aspect = args.stringArg("aspect")?.lowercase()
        return ToolResult.ok(AudioPathReport.message(snapshot, aspect), AudioPathReport.facts(snapshot))
    }

    override fun progressLabel(args: Map<String, Any?>) = "Xem đường tín hiệu"
}
