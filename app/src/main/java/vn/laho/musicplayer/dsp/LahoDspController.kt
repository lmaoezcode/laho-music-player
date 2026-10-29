// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import chromahub.rhythm.app.shared.data.model.AppSettings
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns LaHo's DSP state for the whole app.
 *
 * The PEQ comes either from Rhythm's 10-band graphic EQ settings
 * (AppSettings.equalizerEnabled / equalizerBandLevels; the default, so the existing EQ screen
 * keeps working) or from the active preset of the LaHo PEQ editor, depending on
 * [PeqPresetState.source] (see [PeqSourceSelector]). While LaHo DSP is active the platform
 * `audiofx.Equalizer` is kept flat so the EQ is not applied twice.
 */
object LahoDspController {

    private const val TAG = "LahoDsp"

    private val processors = CopyOnWriteArraySet<PeqProcessor>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bindJob: Job? = null

    /** True once a LaHo DSP sink has been created for the player. */
    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var currentConfig: PeqConfig = PeqConfig.FLAT
        private set

    /** Total samples hard-clipped at the DSP output since start (diagnostics). */
    val clippedSamples = AtomicLong()

    /** Wraps [delegate] (which must have float output enabled) with the LaHo DSP. */
    @OptIn(UnstableApi::class)
    fun createSink(
        delegate: AudioSink,
        appSettings: AppSettings,
        presetState: StateFlow<PeqPresetState>? = null,
    ): AudioSink {
        bind(appSettings, presetState)
        isActive = true
        val processor = PeqProcessor()
        processors += processor
        processor.setConfig(currentConfig)
        Log.i(TAG, "LaHo DSP sink created (32-bit float PEQ)")
        return LahoDspAudioSink(
            delegate = delegate,
            peq = processor,
            onClipped = { clippedSamples.addAndGet(it.toLong()) },
            onRelease = { processors -= processor },
        )
    }

    /** Pushes a new PEQ to every live sink (e.g. an imported AutoEQ ParametricEQ preset). */
    fun setConfig(config: PeqConfig) {
        currentConfig = config
        processors.forEach { it.setConfig(config) }
    }

    @Synchronized
    private fun bind(appSettings: AppSettings, presetState: StateFlow<PeqPresetState>?) {
        if (bindJob != null) return
        bindJob = scope.launch {
            val graphic = combine(appSettings.equalizerEnabled, appSettings.equalizerBandLevels) { enabled, levels ->
                PeqConfig.fromGraphicEq(enabled, parseLevels(levels))
            }
            val selected = if (presetState == null) graphic
            else combine(graphic, presetState) { g, s -> PeqSourceSelector.select(s, g) }
            selected
                .distinctUntilChanged()
                .collect { config ->
                    Log.d(TAG, "PEQ update: enabled=${config.enabled} preamp=${"%.2f".format(config.preampDb)} dB")
                    setConfig(config)
                }
        }
    }

    internal fun parseLevels(levels: String): List<Float> {
        val parsed = levels.split(",").mapNotNull { it.trim().toFloatOrNull() }
        val count = PeqConfig.GRAPHIC_EQ_FREQUENCIES.size
        return List(count) { parsed.getOrElse(it) { 0f } }
    }
}
