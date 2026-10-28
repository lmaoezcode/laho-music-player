// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.laho.musicplayer.dsp.AutoEqParser
import vn.laho.musicplayer.dsp.FilterType
import vn.laho.musicplayer.dsp.LahoDspController
import vn.laho.musicplayer.dsp.PeqBand
import vn.laho.musicplayer.dsp.PeqConfig
import vn.laho.musicplayer.dsp.PeqPreset
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqPresetStore
import vn.laho.musicplayer.dsp.PeqSource

class PeqEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PeqPresetStore.get(app)
    private val appSettings = AppSettings.getInstance(app)

    val state: StateFlow<PeqPresetState> = store.state
    val dspEnabled: StateFlow<Boolean> = appSettings.lahoDspEnabled

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun setSource(source: PeqSource) = store.update { it.copy(source = source) }

    fun select(name: String) = store.update { it.activate(name) }

    /** New preset seeded from Rhythm's current graphic EQ, so the user starts from what they hear. */
    fun newPreset(name: String) {
        val levels = LahoDspController.parseLevels(appSettings.equalizerBandLevels.value)
        val config = PeqConfig.fromGraphicEq(enabled = true, levelsDb = levels)
        store.update { s ->
            val unique = s.uniqueName(name)
            s.withPreset(PeqPreset(unique, config)).activate(unique).copy(source = PeqSource.PRESET)
        }
    }

    fun rename(oldName: String, newName: String) = store.update { it.rename(oldName, newName) }

    fun delete(name: String) = store.update { it.remove(name) }

    fun importAutoEq(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }
                    displayName(uri) to AutoEqParser.parse(text)
                }.getOrNull()
            }
            if (loaded == null) {
                _messages.tryEmit(app.getString(R.string.laho_peq_import_error))
                return@launch
            }
            val (fileName, result) = loaded
            if (result.config.bands.isEmpty()) {
                _messages.tryEmit(app.getString(R.string.laho_peq_import_failed))
                return@launch
            }
            store.update { s ->
                val unique = s.uniqueName(AutoEqParser.presetNameFromFile(fileName))
                s.withPreset(PeqPreset(unique, result.config)).activate(unique).copy(source = PeqSource.PRESET)
            }
            val bands = result.config.bands.size
            _messages.tryEmit(
                if (result.warnings.isEmpty()) app.getString(R.string.laho_peq_import_done, bands)
                else app.getString(R.string.laho_peq_import_done_warnings, bands, result.warnings.size)
            )
        }
    }

    fun setEnabled(enabled: Boolean) = store.update { s -> s.updateActive { it.copy(enabled = enabled) } }

    fun setPreamp(db: Double) = store.update { s -> s.updateActive { it.copy(preampDb = db) } }

    fun autoPreamp() = store.update { s -> s.updateActive { it.withAutoPreamp() } }

    fun updateBand(index: Int, band: PeqBand) = store.update { s ->
        s.updateActive { c ->
            if (index !in c.bands.indices) c
            else c.copy(bands = c.bands.toMutableList().also { it[index] = band })
        }
    }

    fun addBand() = store.update { s ->
        s.updateActive { c -> c.copy(bands = c.bands + PeqBand(FilterType.PEAKING, 1000.0, 0.0, 1.0)) }
    }

    fun removeBand(index: Int) = store.update { s ->
        s.updateActive { c ->
            if (index !in c.bands.indices) c
            else c.copy(bands = c.bands.toMutableList().also { it.removeAt(index) })
        }
    }

    override fun onCleared() {
        store.saveNow()
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    companion object {
        private const val MAX_IMPORT_BYTES = 256 * 1024

        private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < max) {
                val n = read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}
