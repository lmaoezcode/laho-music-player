// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import vn.laho.musicplayer.dsp.PeqConfig
import vn.laho.musicplayer.dsp.PeqResponse

private const val POINTS = 240
private val GRID_HZ = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)

/**
 * Log-frequency response plot (20 Hz–20 kHz) computed from the real biquad coefficients.
 * Solid line: bands + preamp (what reaches the output). Dashed: bands only.
 */
@Composable
fun PeqResponseGraph(config: PeqConfig, contentDescription: String, modifier: Modifier = Modifier) {
    val freqs = remember { PeqResponse.logFrequencies(POINTS) }
    val total = remember(config) { PeqResponse.curveDb(config, freqs, includePreamp = true) }
    val bandsOnly = remember(config) { PeqResponse.curveDb(config, freqs, includePreamp = false) }
    // Symmetric dB range: at least ±12 dB, grows in 6 dB steps to fit the curve.
    val range = remember(total, bandsOnly) {
        val peak = max(total.maxOf { abs(it) }, bandsOnly.maxOf { abs(it) })
        max(12.0, ceil(peak / 6.0) * 6.0).coerceAtMost(36.0)
    }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline
    val markers = MaterialTheme.colorScheme.secondary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { this.contentDescription = contentDescription }
    ) {
        val w = size.width
        val h = size.height
        fun x(f: Double) = (PeqResponse.fractionOf(f) * w).toFloat()
        fun y(db: Double) = (h / 2 - (db.coerceIn(-range, range) / range) * (h / 2)).toFloat()

        GRID_HZ.forEach { f ->
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), h), strokeWidth = 1f)
        }
        var db = -range
        while (db <= range) {
            drawLine(if (db == 0.0) zero else grid, Offset(0f, y(db)), Offset(w, y(db)), strokeWidth = if (db == 0.0) 2f else 1f)
            db += 6.0
        }

        fun path(values: DoubleArray) = Path().apply {
            values.forEachIndexed { i, v ->
                val px = x(freqs[i])
                val py = y(v)
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(
            path(bandsOnly),
            color = secondary.copy(alpha = 0.7f),
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
        )
        drawPath(path(total), color = primary, style = Stroke(width = 3.dp.toPx()))

        config.bands.filter { it.enabled && it.frequencyHz in PeqResponse.MIN_HZ..PeqResponse.MAX_HZ }.forEach { b ->
            val i = freqs.indices.minByOrNull { abs(freqs[it] - b.frequencyHz) } ?: return@forEach
            drawCircle(markers, radius = 4.dp.toPx(), center = Offset(x(b.frequencyHz), y(total[i])))
        }
    }
}
