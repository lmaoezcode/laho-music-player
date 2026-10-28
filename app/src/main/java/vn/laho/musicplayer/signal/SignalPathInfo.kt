// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.signal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * LaHo's own output path for the current track, shown in the "Signal path" section of the
 * playback quality sheet and as a badge. Null in [chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo]
 * when neither LaHo mode is active (Rhythm's own telemetry is shown unchanged).
 */
@Parcelize
data class SignalPathInfo(
    val mode: Mode,
    /** USB: stream rate (= file rate). DSP: 0 (Android's mixer decides the output rate). */
    val sampleRateHz: Int = 0,
    /** USB: rate the DAC reports after SET_CUR, <= 0 if it does not answer GET_CUR. */
    val dacReportedRateHz: Int = 0,
    val sourceBitDepth: Int = 0,
    /** USB: bits per sample on the wire. DSP: 32 (float). */
    val outputBitDepth: Int = 0,
    val engine: Engine = Engine.NONE,
    val dacName: String = "",
    val isBitPerfect: Boolean = false,
    /** Common rates the DAC accepts (GET RANGE); empty if unknown. */
    val dacSupportedRates: List<Int> = emptyList(),
    /** USB: the track's rate when it had to fall back to Android's pipeline. */
    val fallbackRateHz: Int = 0,
    val fallbackReason: FallbackReason? = null,
    /** DSP: whether the PEQ is enabled and how many bands it runs. */
    val peqEnabled: Boolean = false,
    val peqBandCount: Int = 0,
) : Parcelable {

    enum class Mode { USB_DRIVER, USB_FALLBACK, LAHO_DSP }

    enum class Engine { NONE, NATIVE_FLAC, EXOPLAYER, FLOAT_DSP }

    enum class FallbackReason { RATE_NOT_SUPPORTED, PACKET_TOO_LARGE, URB_BUFFER_TOO_SMALL, RATE_NOT_APPLIED, STREAM_FAILED }

    /** Badge shown next to the quality badges. */
    enum class Badge { BIT_PERFECT, USB, PEQ, DSP, NONE }

    val badge: Badge
        get() = when (mode) {
            Mode.USB_DRIVER -> if (isBitPerfect) Badge.BIT_PERFECT else Badge.USB
            Mode.LAHO_DSP -> if (peqEnabled && peqBandCount > 0) Badge.PEQ else Badge.DSP
            Mode.USB_FALLBACK -> Badge.NONE
        }
}
