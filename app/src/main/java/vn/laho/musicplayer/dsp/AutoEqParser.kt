// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp

/**
 * Parses AutoEQ / Equalizer APO "ParametricEQ.txt" presets, e.g.
 *
 * ```
 * Preamp: -6.2 dB
 * Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
 * Filter 2: ON PK Fc 2000 Hz Gain -3.1 dB Q 1.41
 * Filter 3: OFF HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
 * ```
 *
 * Unknown lines (comments, `Device:` headers, unsupported filter types) are skipped and
 * reported in [Result.warnings] instead of failing the whole import.
 */
object AutoEqParser {

    data class Result(val config: PeqConfig, val warnings: List<String>)

    private val PREAMP = Regex("""^\s*Preamp\s*:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)""" +
            """(?:\s+Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz)?""" +
            """(?:\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB)?""" +
            """(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Result {
        var preamp = 0.0
        val bands = mutableListOf<PeqBand>()
        val warnings = mutableListOf<String>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            PREAMP.find(line)?.let {
                preamp = it.groupValues[1].toDouble()
                return@forEachIndexed
            }

            val match = FILTER.find(line)
            if (match == null) {
                warnings += "Line ${index + 1}: ignored \"$line\""
                return@forEachIndexed
            }
            val (state, code, fc, gain, q) = match.destructured
            val type = filterTypeOf(code.uppercase())
            if (type == null) {
                warnings += "Line ${index + 1}: unsupported filter type $code"
                return@forEachIndexed
            }
            if (fc.isEmpty()) {
                warnings += "Line ${index + 1}: missing Fc"
                return@forEachIndexed
            }
            bands += PeqBand(
                type = type,
                frequencyHz = fc.toDouble(),
                gainDb = gain.toDoubleOrNull() ?: 0.0,
                q = q.toDoubleOrNull() ?: PeqBand.DEFAULT_Q,
                enabled = state.equals("ON", ignoreCase = true),
            )
        }
        return Result(PeqConfig(enabled = true, preampDb = preamp, bands = bands), warnings)
    }

    private fun filterTypeOf(code: String): FilterType? = when (code) {
        "PK", "PEQ", "PEAKING" -> FilterType.PEAKING
        "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
        "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
        "LP", "LPQ" -> FilterType.LOW_PASS
        "HP", "HPQ" -> FilterType.HIGH_PASS
        else -> null
    }
}
