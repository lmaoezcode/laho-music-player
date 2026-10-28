// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.signal

import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import com.decent.usbaudio.UsbRateRejection
import com.decent.usbaudio.media3.UsbEngine
import com.decent.usbaudio.media3.UsbSignalPath
import vn.laho.musicplayer.dsp.LahoDspController
import vn.laho.musicplayer.dsp.PeqConfig
import vn.laho.musicplayer.usb.LahoUsbAudio

/**
 * Builds [SignalPathInfo] for LaHo's output modes and merges it into Rhythm's
 * [PlaybackQualityInfo] (one call from MediaPlaybackService.updatePlaybackQuality).
 *
 * Everything except [current] is pure and unit-tested.
 */
object LahoSignalPath {

    /** Current LaHo path, or null when the app uses Rhythm's standard pipeline. */
    fun current(settings: AppSettings): SignalPathInfo? {
        if (LahoUsbAudio.isEnabled(settings)) return fromUsb(LahoUsbAudio.signalPath.value)
        val dspOn = settings.lahoDspEnabled.value &&
            settings.audioRoutingMode.value != "app" &&
            LahoDspController.isActive
        return if (dspOn) fromDsp(LahoDspController.currentConfig) else null
    }

    fun fromUsb(path: UsbSignalPath): SignalPathInfo? = when {
        path.active -> SignalPathInfo(
            mode = SignalPathInfo.Mode.USB_DRIVER,
            sampleRateHz = path.sampleRateHz,
            dacReportedRateHz = path.dacReportedRateHz,
            sourceBitDepth = path.sourceBitDepth,
            outputBitDepth = path.usbBitDepth,
            engine = when (path.engine) {
                UsbEngine.NATIVE_FLAC -> SignalPathInfo.Engine.NATIVE_FLAC
                UsbEngine.EXOPLAYER -> SignalPathInfo.Engine.EXOPLAYER
                UsbEngine.NONE -> SignalPathInfo.Engine.NONE
            },
            dacName = path.dacName,
            isBitPerfect = path.bitPerfect,
            dacSupportedRates = path.supportedRates,
        )
        path.fallbackReason != null -> SignalPathInfo(
            mode = SignalPathInfo.Mode.USB_FALLBACK,
            dacName = path.dacName,
            dacSupportedRates = path.supportedRates,
            fallbackRateHz = path.fallbackRateHz,
            fallbackReason = map(path.fallbackReason),
        )
        else -> null
    }

    fun fromDsp(config: PeqConfig): SignalPathInfo {
        val bands = if (config.enabled) config.bands.count { it.enabled } else 0
        return SignalPathInfo(
            mode = SignalPathInfo.Mode.LAHO_DSP,
            outputBitDepth = 32,
            engine = SignalPathInfo.Engine.FLOAT_DSP,
            peqEnabled = config.enabled,
            peqBandCount = bands,
        )
    }

    /**
     * Overrides Rhythm's output fields while the LaHo USB driver streams (Android's
     * AudioManager does not see the claimed DAC, so Rhythm would report the speaker).
     */
    fun applyTo(quality: PlaybackQualityInfo, path: SignalPathInfo?): PlaybackQualityInfo {
        if (path == null) return quality
        return when (path.mode) {
            SignalPathInfo.Mode.USB_DRIVER -> quality.copy(
                outputDeviceName = path.dacName.ifBlank { "USB DAC" },
                outputDeviceType = if (path.isBitPerfect) "USB DAC (LaHo driver, Bit-Perfect)" else "USB DAC (LaHo driver)",
                isBluetooth = false,
                bluetoothCodec = null,
                bluetoothBitrate = null,
                bluetoothSampleRateHz = null,
                bluetoothBitDepth = null,
                isResampled = false,
                resamplingDescription = "Direct ${PlaybackQualityInfo.formatSampleRate(path.sampleRateHz)} " +
                    "to the DAC (LaHo USB driver, no Android mixer)",
                isBitPerfect = path.isBitPerfect,
                outputQualityStatus = if (path.isBitPerfect) {
                    "Bit-Perfect • LaHo USB driver"
                } else {
                    "LaHo USB driver • ${PlaybackQualityInfo.formatSampleRate(path.sampleRateHz)}"
                },
                lahoSignalPath = path,
            )
            SignalPathInfo.Mode.USB_FALLBACK,
            SignalPathInfo.Mode.LAHO_DSP -> quality.copy(isBitPerfect = false, lahoSignalPath = path)
        }
    }

    fun map(reason: UsbRateRejection?): SignalPathInfo.FallbackReason? = when (reason) {
        null -> null
        UsbRateRejection.RATE_NOT_SUPPORTED -> SignalPathInfo.FallbackReason.RATE_NOT_SUPPORTED
        UsbRateRejection.PACKET_TOO_LARGE -> SignalPathInfo.FallbackReason.PACKET_TOO_LARGE
        UsbRateRejection.URB_BUFFER_TOO_SMALL -> SignalPathInfo.FallbackReason.URB_BUFFER_TOO_SMALL
        UsbRateRejection.RATE_NOT_APPLIED -> SignalPathInfo.FallbackReason.RATE_NOT_APPLIED
        UsbRateRejection.STREAM_FAILED -> SignalPathInfo.FallbackReason.STREAM_FAILED
    }
}
