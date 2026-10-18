// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.mood

/**
 * Library-relative calibration of audio arousal.
 *
 * Fixed thresholds saturate on real music: modern masters are all loud (-8…-20 dBFS) and
 * busy, so measured on the Mi 11 Ultra library every song came out "energetic" (0.62–0.94).
 * Instead each feature is converted to its percentile rank within the user's own library,
 * the ranks are combined with the same weights as [AudioFeatures.arousal], and the composite
 * is ranked again so the result spans 0..1. To keep an absolute anchor (an all-metal library
 * should not turn half its songs "calm") the final value is
 * `RELATIVE_WEIGHT * relative + (1 - RELATIVE_WEIGHT) * absolute`.
 */
class LibraryCalibration private constructor(
    private val rms: DoubleArray,
    private val bpm: DoubleArray,
    private val centroid: DoubleArray,
    private val flux: DoubleArray,
    private val composites: DoubleArray,
) {
    val size: Int get() = composites.size

    /** Calibrated audio arousal in 0..1, or null if the record has no audio features. */
    fun arousal(record: MoodRecord): Double? {
        val f = record.features() ?: return null
        val relative = rank(composites, composite(f))
        return RELATIVE_WEIGHT * relative + (1 - RELATIVE_WEIGHT) * f.arousal()
    }

    private fun composite(f: AudioFeatures): Double =
        0.35 * rank(rms, f.rmsDb) +
            0.25 * (if (f.bpm > 0 && bpm.isNotEmpty()) rank(bpm, f.bpm) else 0.5) +
            0.2 * rank(centroid, f.spectralCentroidHz) +
            0.2 * rank(flux, f.spectralFlux)

    companion object {
        /** Below this many analysed songs percentiles are meaningless; use absolute scale. */
        const val MIN_SONGS = 8
        const val RELATIVE_WEIGHT = 0.7

        fun from(records: List<MoodRecord>): LibraryCalibration? {
            val features = records.mapNotNull { it.features() }
            if (features.size < MIN_SONGS) return null
            val rms = features.map { it.rmsDb }.sorted().toDoubleArray()
            val bpm = features.map { it.bpm }.filter { it > 0 }.sorted().toDoubleArray()
            val centroid = features.map { it.spectralCentroidHz }.sorted().toDoubleArray()
            val flux = features.map { it.spectralFlux }.sorted().toDoubleArray()
            val partial = LibraryCalibration(rms, bpm, centroid, flux, DoubleArray(0))
            val composites = features.map { partial.composite(it) }.sorted().toDoubleArray()
            return LibraryCalibration(rms, bpm, centroid, flux, composites)
        }

        /**
         * Mid-rank percentile of [x] in [sorted]: (below + equal / 2) / n, so the lowest of
         * n distinct values maps to 0.5/n and the highest to 1 - 0.5/n.
         */
        internal fun rank(sorted: DoubleArray, x: Double): Double {
            if (sorted.isEmpty()) return 0.5
            var below = 0
            var equal = 0
            for (v in sorted) {
                if (v < x) below++ else if (v == x) equal++ else break
            }
            return (below + equal / 2.0) / sorted.size
        }
    }
}
