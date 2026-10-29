// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

import org.json.JSONArray
import org.json.JSONObject

/** Where the LaHo DSP takes its PEQ from. */
enum class PeqSource {
    /** Rhythm's 10-band graphic EQ (AppSettings), the original spike behaviour. */
    GRAPHIC_EQ,

    /** The active named preset from the LaHo PEQ editor. */
    PRESET,
}

/** A named parametric EQ preset. */
data class PeqPreset(val name: String, val config: PeqConfig)

/**
 * Everything the PEQ editor persists. Immutable; every edit returns a new state.
 * The default state keeps the original behaviour (graphic EQ drives the DSP).
 */
data class PeqPresetState(
    val source: PeqSource = PeqSource.GRAPHIC_EQ,
    val activeName: String? = null,
    val presets: List<PeqPreset> = emptyList(),
) {
    val activePreset: PeqPreset? get() = presets.firstOrNull { it.name == activeName }

    /** Adds [preset], replacing any preset with the same name. */
    fun withPreset(preset: PeqPreset): PeqPresetState {
        val index = presets.indexOfFirst { it.name == preset.name }
        val list = if (index >= 0) presets.toMutableList().also { it[index] = preset } else presets + preset
        return copy(presets = list)
    }

    fun activate(name: String): PeqPresetState =
        if (presets.any { it.name == name }) copy(activeName = name) else this

    /** Removes a preset. Falls back to another preset, or to the graphic EQ if none are left. */
    fun remove(name: String): PeqPresetState {
        val list = presets.filterNot { it.name == name }
        val active = if (activeName == name) list.firstOrNull()?.name else activeName
        val source = if (list.isEmpty()) PeqSource.GRAPHIC_EQ else source
        return copy(source = source, activeName = active, presets = list)
    }

    /** Renames [oldName]; returns this unchanged if [newName] is blank or already used. */
    fun rename(oldName: String, newName: String): PeqPresetState {
        val clean = newName.trim()
        if (clean.isEmpty() || presets.any { it.name == clean }) return this
        val list = presets.map { if (it.name == oldName) it.copy(name = clean) else it }
        return copy(presets = list, activeName = if (activeName == oldName) clean else activeName)
    }

    /** Applies [transform] to the active preset's config (no-op without an active preset). */
    fun updateActive(transform: (PeqConfig) -> PeqConfig): PeqPresetState {
        val active = activePreset ?: return this
        return withPreset(active.copy(config = transform(active.config)))
    }

    /** [base], or "base (2)", "base (3)"… so names stay unique. */
    fun uniqueName(base: String): String {
        val clean = base.trim().ifEmpty { "Preset" }
        if (presets.none { it.name == clean }) return clean
        var n = 2
        while (presets.any { it.name == "$clean ($n)" }) n++
        return "$clean ($n)"
    }

    companion object {
        val DEFAULT = PeqPresetState()
    }
}

/**
 * Chooses the config the DSP should run. A preset source with no active preset falls back to
 * the graphic EQ, so the DSP never ends up with no EQ by accident.
 */
object PeqSourceSelector {
    fun select(state: PeqPresetState, graphicEq: PeqConfig): PeqConfig =
        when (state.source) {
            PeqSource.GRAPHIC_EQ -> graphicEq
            PeqSource.PRESET -> state.activePreset?.config ?: graphicEq
        }
}

/** JSON (de)serialisation of [PeqPresetState]. Tolerant: bad entries are skipped. */
object PeqPresetCodec {

    private const val VERSION = 1

    fun encode(state: PeqPresetState): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("source", state.source.name)
        state.activeName?.let { root.put("active", it) }
        val presets = JSONArray()
        state.presets.forEach { presets.put(encodePreset(it)) }
        root.put("presets", presets)
        return root.toString(2)
    }

    fun decode(text: String): PeqPresetState = try {
        val root = JSONObject(text)
        val presets = mutableListOf<PeqPreset>()
        val array = root.optJSONArray("presets") ?: JSONArray()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            decodePreset(p)?.takeIf { d -> presets.none { it.name == d.name } }?.let { presets += it }
        }
        val source = runCatching { PeqSource.valueOf(root.optString("source")) }
            .getOrDefault(PeqSource.GRAPHIC_EQ)
        val active = root.optString("active", "").takeIf { name -> presets.any { it.name == name } }
        PeqPresetState(source, active ?: presets.firstOrNull()?.name, presets)
    } catch (e: Exception) {
        PeqPresetState.DEFAULT
    }

    private fun encodePreset(preset: PeqPreset): JSONObject {
        val c = preset.config
        val bands = JSONArray()
        c.bands.forEach { b ->
            bands.put(
                JSONObject()
                    .put("type", b.type.name)
                    .put("frequencyHz", b.frequencyHz)
                    .put("gainDb", b.gainDb)
                    .put("q", b.q)
                    .put("enabled", b.enabled)
            )
        }
        return JSONObject()
            .put("name", preset.name)
            .put("enabled", c.enabled)
            .put("preampDb", c.preampDb)
            .put("bands", bands)
    }

    private fun decodePreset(o: JSONObject): PeqPreset? {
        val name = o.optString("name", "").trim().ifEmpty { return null }
        val bands = mutableListOf<PeqBand>()
        val array = o.optJSONArray("bands") ?: JSONArray()
        for (i in 0 until array.length()) {
            val b = array.optJSONObject(i) ?: continue
            val type = runCatching { FilterType.valueOf(b.optString("type")) }.getOrNull() ?: continue
            val f = b.optDouble("frequencyHz", Double.NaN)
            if (f.isNaN() || f <= 0.0) continue
            bands += PeqBand(
                type = type,
                frequencyHz = f,
                gainDb = b.optDouble("gainDb", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                q = b.optDouble("q", PeqBand.DEFAULT_Q).takeIf { it > 0.0 } ?: PeqBand.DEFAULT_Q,
                enabled = b.optBoolean("enabled", true),
            )
        }
        val preamp = o.optDouble("preampDb", 0.0).takeUnless { it.isNaN() } ?: 0.0
        return PeqPreset(name, PeqConfig(o.optBoolean("enabled", true), preamp, bands))
    }
}
