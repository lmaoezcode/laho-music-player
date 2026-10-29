// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.audio

import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo

/** Bluetooth A2DP/LE audio codecs LaHo can name. */
enum class BluetoothCodec(val label: String, val nominalKbps: Int?) {
    SBC("SBC", 328),
    AAC("AAC", 256),
    APTX("aptX", 352),
    APTX_HD("aptX HD", 576),
    APTX_ADAPTIVE("aptX Adaptive", null),
    LDAC("LDAC", null),
    LC3("LC3", null),
    OPUS("Opus", null),
    LHDC("LHDC", null),
    /** A codec the system reports but LaHo has no name for. */
    OTHER("", null),
    /** The system did not tell us (privileged API, no permission, old Android). */
    UNKNOWN("", null),
}

/** How the UI/assistant must qualify a Bluetooth path. Bluetooth is never bit-perfect. */
enum class BluetoothWarning {
    /** Codec could not be read: say so, still not bit-perfect. */
    UNKNOWN_CODEC,
    /** Lossy compression (SBC, AAC, aptX, LC3...). */
    LOSSY,
    /** High-bitrate lossy codec (LDAC, aptX HD/Adaptive, LHDC): closer to the source, still not bit-perfect. */
    LOSSY_HIGH_BITRATE,
}

/**
 * What LaHo knows about the Bluetooth leg of the output. Pure data: built by [from] from the
 * values Rhythm already reads (see `BtCodecInfo`), so the mapping and fallbacks are unit-tested.
 */
data class BluetoothAudioPath(
    val codec: BluetoothCodec,
    /** Name as the system reported it (only meaningful for [BluetoothCodec.OTHER]). */
    val reportedName: String?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val bitrateKbps: Int?,
    /** True when [bitrateKbps] is the codec's typical rate, not read from the link. */
    val bitrateApproximate: Boolean,
    /** BLUETOOTH_CONNECT granted (always true below Android 12). */
    val permissionGranted: Boolean,
) {
    val isCodecKnown: Boolean get() = codec != BluetoothCodec.UNKNOWN

    /** Over Bluetooth the signal is always compressed and resampled by the stack. */
    val isBitPerfect: Boolean get() = false

    val codecDisplayName: String
        get() = when (codec) {
            BluetoothCodec.UNKNOWN -> ""
            BluetoothCodec.OTHER -> reportedName.orEmpty()
            else -> codec.label
        }

    val warning: BluetoothWarning
        get() = when (codec) {
            BluetoothCodec.UNKNOWN -> BluetoothWarning.UNKNOWN_CODEC
            BluetoothCodec.LDAC, BluetoothCodec.APTX_HD, BluetoothCodec.APTX_ADAPTIVE, BluetoothCodec.LHDC ->
                BluetoothWarning.LOSSY_HIGH_BITRATE
            else -> BluetoothWarning.LOSSY
        }

    /** The codec could be read but the permission to do so is missing: worth asking. */
    val needsPermissionPrompt: Boolean get() = !isCodecKnown && !permissionGranted

    companion object {
        private val KBPS = Regex("(\\d{2,4})\\s*kbps", RegexOption.IGNORE_CASE)

        /** Maps the name given by `BluetoothCodecConfig` / `BtCodecInfo.codec` ("aptX HD", "INVALID CODEC", "A2DP"...). */
        fun codecOf(name: String?): BluetoothCodec {
            val n = name?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
            return when {
                n.isEmpty() || n == "a2dp" || n.startsWith("invalidcodec") || n.startsWith("unknowncodec") ||
                    n == "unknown" -> BluetoothCodec.UNKNOWN
                n == "sbc" -> BluetoothCodec.SBC
                n == "aac" -> BluetoothCodec.AAC
                n.startsWith("aptxadaptive") -> BluetoothCodec.APTX_ADAPTIVE
                n.startsWith("aptxhd") -> BluetoothCodec.APTX_HD
                n.startsWith("aptx") -> BluetoothCodec.APTX
                n == "ldac" -> BluetoothCodec.LDAC
                n.startsWith("lc3") -> BluetoothCodec.LC3
                n.startsWith("opus") -> BluetoothCodec.OPUS
                n.startsWith("lhdc") -> BluetoothCodec.LHDC
                else -> BluetoothCodec.OTHER
            }
        }

        /**
         * @param codecName `BtCodecInfo.codec` (null or "A2DP" when unreadable)
         * @param quality `BtCodecInfo.quality`, e.g. "990kbps" for LDAC or "Auto"
         */
        fun from(
            codecName: String?,
            quality: String?,
            sampleRateHz: Int?,
            bitsPerSample: Int?,
            permissionGranted: Boolean,
        ): BluetoothAudioPath {
            val codec = codecOf(codecName)
            val read = quality?.let { KBPS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val bitrate = read ?: codec.nominalKbps
            return BluetoothAudioPath(
                codec = codec,
                reportedName = codecName?.trim()?.takeIf { it.isNotEmpty() && codec == BluetoothCodec.OTHER },
                sampleRateHz = sampleRateHz?.takeIf { it > 0 },
                bitsPerSample = bitsPerSample?.takeIf { it > 0 },
                bitrateKbps = bitrate,
                bitrateApproximate = read == null && bitrate != null,
                permissionGranted = permissionGranted,
            )
        }
    }
}

/** Bluetooth leg of this output, or null when the output is not Bluetooth. */
fun PlaybackQualityInfo.bluetoothAudioPath(permissionGranted: Boolean): BluetoothAudioPath? =
    if (!isBluetooth) null
    else BluetoothAudioPath.from(bluetoothCodec, bluetoothBitrate, bluetoothSampleRateHz, bluetoothBitDepth, permissionGranted)
