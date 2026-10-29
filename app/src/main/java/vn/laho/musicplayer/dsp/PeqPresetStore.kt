// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persists [PeqPresetState] as JSON in the app's files dir (`laho_peq_presets.json`).
 *
 * [state] changes immediately so the DSP hears edits live; writes to disk are debounced,
 * because sliders can produce dozens of edits per second.
 */
class PeqPresetStore(
    private val file: File,
    private val saveDelayMs: Long = 400,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PeqPresetState> = _state.asStateFlow()

    private val version = AtomicLong()

    fun update(transform: (PeqPresetState) -> PeqPresetState) {
        _state.update(transform)
        val v = version.incrementAndGet()
        scope.launch {
            delay(saveDelayMs)
            if (version.get() == v) saveNow()
        }
    }

    /** Writes the current state atomically (temp file + rename). */
    @Synchronized
    fun saveNow() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(PeqPresetCodec.encode(_state.value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): PeqPresetState =
        if (file.isFile) PeqPresetCodec.decode(runCatching { file.readText() }.getOrDefault(""))
        else PeqPresetState.DEFAULT

    companion object {
        private const val FILE_NAME = "laho_peq_presets.json"

        @Volatile
        private var instance: PeqPresetStore? = null

        fun get(context: Context): PeqPresetStore =
            instance ?: synchronized(this) {
                instance ?: PeqPresetStore(File(context.applicationContext.filesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}
