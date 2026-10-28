// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.usb

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import android.widget.Toast
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioPermissionHelper
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbSignalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.laho.musicplayer.signal.LahoSignalPath
import vn.laho.musicplayer.signal.SignalPathInfo

/**
 * Glue between LaHo and the vendored decent-player USB Audio Class 2.0 driver
 * (third_party/decent-usb-audio, MIT).
 *
 * When "LaHo USB driver" is enabled, the app claims the USB DAC itself (before the kernel's
 * snd-usb-audio driver binds) and streams PCM to it from userspace, bypassing AudioFlinger's
 * mixer and resampler: bit-perfect output on Android 10+, without needing the Android 14
 * AudioMixerAttributes API. No DSP runs in this mode.
 *
 * Only the engine's primary player gets the USB sink (the driver supports one sink per DAC);
 * crossfade is therefore not supported in this mode.
 */
object LahoUsbAudio {

    private const val TAG = "LahoUsb"

    /** Minimum API level of the vendored driver. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isEnabled(settings: AppSettings): Boolean = isSupported && settings.lahoUsbDriverEnabled.value

    @Volatile
    private var currentSink: AudioSink? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var relayJob: Job? = null
    private val _signalPath = MutableStateFlow(UsbSignalPath.INACTIVE)

    /**
     * Signal path of the USB sink of the primary player (rate, bit depth, engine, DAC,
     * bit-perfect, fallback). Survives sink re-creation; INACTIVE when no stream runs.
     */
    val signalPath: StateFlow<UsbSignalPath> = _signalPath.asStateFlow()

    /** Last fallback the user was told about, so a toast is shown once per track change. */
    private var lastToastedFallback: Pair<Int, Any?>? = null

    /**
     * Call from the main activity. A USB_DEVICE_ATTACHED intent is handled immediately so the
     * DAC is claimed before the kernel audio driver binds; on a normal launch ([isLaunch]) a
     * DAC that is already plugged in is claimed instead.
     */
    fun onActivityIntent(context: Context, intent: Intent?, isLaunch: Boolean) {
        if (!isEnabled(AppSettings.getInstance(context))) return
        if (!isSupported) return
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleIntentApi29(context, intent)
        } else if (isLaunch) {
            claimApi29(context.applicationContext)
        }
    }

    /**
     * Claims a DAC that is already plugged in (asking for USB permission if needed). Call at
     * app start and when the setting is switched on.
     */
    fun claimConnectedDac(context: Context) {
        if (!isEnabled(AppSettings.getInstance(context))) return
        if (!isSupported) return
        claimApi29(context.applicationContext)
    }

    /** Builds the USB sink for the primary player. Requires [isEnabled]. */
    @OptIn(UnstableApi::class)
    fun createSink(context: Context): AudioSink {
        check(isSupported) { "USB driver needs Android 10+" }
        return createSinkApi29(context)
    }

    @OptIn(UnstableApi::class)
    fun wrapLoadControl(loadControl: LoadControl): LoadControl {
        if (!isSupported) return loadControl
        return UsbAudioSink.wrapLoadControl(loadControl) {
            (currentSink as? UsbAudioSink)?.isNativeEngineActive == true
        }
    }

    @OptIn(UnstableApi::class)
    fun attach(player: Player) {
        (currentSink as? UsbAudioSink)?.attachToPlayer(player)
        Log.i(TAG, "USB sink attached to primary player")
    }

    @OptIn(UnstableApi::class)
    private fun relay(appContext: Context, sink: UsbAudioSink) {
        relayJob?.cancel()
        relayJob = scope.launch {
            sink.signalPath.collect { path ->
                _signalPath.value = path
                notifyFallback(appContext, path)
            }
        }
    }

    /** Tells the user (once per rate/reason) that a track left the bit-perfect USB path. */
    private suspend fun notifyFallback(appContext: Context, path: UsbSignalPath) {
        if (path.active) {
            lastToastedFallback = null
            return
        }
        val reason = path.fallbackReason ?: return
        val key = path.fallbackRateHz to reason
        if (key == lastToastedFallback) return
        lastToastedFallback = key
        val message = appContext.getString(
            R.string.laho_usb_fallback_toast,
            PlaybackQualityInfo.formatSampleRate(path.fallbackRateHz),
            appContext.getString(fallbackReasonText(LahoSignalPath.map(reason)))
        )
        Log.w(TAG, message)
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /** String resource explaining a fallback reason (shared with the quality sheet). */
    fun fallbackReasonText(reason: SignalPathInfo.FallbackReason?): Int = when (reason) {
        SignalPathInfo.FallbackReason.RATE_NOT_SUPPORTED -> R.string.laho_usb_reason_rate_not_supported
        SignalPathInfo.FallbackReason.PACKET_TOO_LARGE,
        SignalPathInfo.FallbackReason.URB_BUFFER_TOO_SMALL -> R.string.laho_usb_reason_packet_too_large
        SignalPathInfo.FallbackReason.RATE_NOT_APPLIED -> R.string.laho_usb_reason_rate_not_applied
        SignalPathInfo.FallbackReason.STREAM_FAILED, null -> R.string.laho_usb_reason_stream_failed
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleIntentApi29(context: Context, intent: Intent) {
        val claimed = UsbAudioPermissionHelper.handleIntent(context.applicationContext, intent)
        if (claimed != null) Log.i(TAG, "DAC claimed on attach: ${claimed.productName}")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun claimApi29(context: Context) {
        val usb = UsbAudioDevice.getInstance(context)
        val device = usb.findUsbAudioDevice() ?: run {
            Log.i(TAG, "No USB audio device connected")
            return
        }
        if (usb.hasPermission(device)) {
            val info = usb.openDevice(device)
            Log.i(TAG, "DAC claimed: ${info?.deviceName ?: device.productName}")
        } else {
            Log.i(TAG, "Requesting USB permission for ${device.productName}")
            usb.requestPermission(device) { granted ->
                if (granted) {
                    val info = usb.openDevice(device)
                    Log.i(TAG, "Permission granted, DAC claimed: ${info?.deviceName}")
                } else {
                    Log.w(TAG, "USB permission denied")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(UnstableApi::class)
    private fun createSinkApi29(context: Context): AudioSink {
        // No processors: bit-perfect. Float output so FFmpeg's float PCM (24-bit exact)
        // reaches the sink, which converts back to the DAC's integer format.
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(true)
            .build()
        return UsbAudioSink(delegate, context.applicationContext).also {
            currentSink = it
            relay(context.applicationContext, it)
            Log.i(TAG, "USB bit-perfect sink created")
        }
    }
}
