// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.dsp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.ui.theme.RhythmTheme
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import vn.laho.musicplayer.dsp.FilterType
import vn.laho.musicplayer.dsp.PeqBand
import vn.laho.musicplayer.dsp.PeqConfig
import vn.laho.musicplayer.dsp.PeqPresetState
import vn.laho.musicplayer.dsp.PeqResponse
import vn.laho.musicplayer.dsp.PeqSource

/** LaHo parametric EQ editor: bands, preamp, response graph, AutoEQ import, presets. */
class PeqEditorActivity : ComponentActivity() {

    private val viewModel: PeqEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RhythmTheme {
                PeqEditorScreen(viewModel, onBack = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, PeqEditorActivity::class.java)
    }
}

private const val GAIN_RANGE = 20f
private const val Q_MIN = 0.1
private const val Q_MAX = 10.0
private const val PREAMP_MIN = -30f
private const val PREAMP_MAX = 6f

private fun fmt(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

private fun formatHz(hz: Double): String =
    if (hz >= 1000) "${fmt(hz / 1000, if (hz >= 10_000) 1 else 2)} kHz" else "${hz.roundToInt()} Hz"

private fun typeCode(type: FilterType) = when (type) {
    FilterType.PEAKING -> "PK"
    FilterType.LOW_SHELF -> "LS"
    FilterType.HIGH_SHELF -> "HS"
    FilterType.LOW_PASS -> "LP"
    FilterType.HIGH_PASS -> "HP"
}

private sealed interface NameDialog {
    data object New : NameDialog
    data class Rename(val oldName: String) : NameDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeqEditorScreen(viewModel: PeqEditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val dspEnabled by viewModel.dspEnabled.collectAsState()

    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importAutoEq(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.laho_peq_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(RhythmIcons.Back, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        val active = state.activePreset
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!dspEnabled) {
                item(key = "dsp_off") {
                    InfoCard(stringResource(R.string.laho_peq_dsp_off), warning = true)
                }
            }
            item(key = "source") { SourceSection(state, viewModel) }
            item(key = "presets") {
                PresetSection(
                    state = state,
                    onSelect = viewModel::select,
                    onNew = { nameDialog = NameDialog.New },
                    onImport = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                    onRename = { active?.let { nameDialog = NameDialog.Rename(it.name) } },
                    onDelete = { deleteTarget = active?.name },
                )
            }
            if (active == null) {
                item(key = "empty") { InfoCard(stringResource(R.string.laho_peq_no_presets)) }
            } else {
                val config = active.config
                item(key = "graph") { GraphSection(config) }
                item(key = "master") { MasterSection(config, viewModel) }
                item(key = "bands_header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.laho_peq_bands, config.bands.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = viewModel::addBand) {
                            Icon(RhythmIcons.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.laho_peq_add_band))
                        }
                    }
                }
                itemsIndexed(config.bands, key = { index, _ -> "band_$index" }) { index, band ->
                    BandCard(
                        index = index,
                        band = band,
                        onChange = { viewModel.updateBand(index, it) },
                        onRemove = { viewModel.removeBand(index) },
                    )
                }
            }
        }
    }

    nameDialog?.let { dialog ->
        val initial = when (dialog) {
            NameDialog.New -> stringResource(R.string.laho_peq_new_default_name)
            is NameDialog.Rename -> dialog.oldName
        }
        TextInputDialog(
            title = stringResource(R.string.laho_peq_name_dialog_title),
            initial = initial,
            keyboardType = KeyboardType.Text,
            onDismiss = { nameDialog = null },
            onConfirm = { text ->
                when (dialog) {
                    NameDialog.New -> viewModel.newPreset(text)
                    is NameDialog.Rename -> viewModel.rename(dialog.oldName, text)
                }
                nameDialog = null
            },
        )
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.laho_peq_delete)) },
            text = { Text(stringResource(R.string.laho_peq_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(name); deleteTarget = null }) {
                    Text(stringResource(R.string.laho_peq_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.laho_peq_cancel)) }
            },
        )
    }
}

@Composable
private fun InfoCard(text: String, warning: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SourceSection(state: PeqPresetState, viewModel: PeqEditorViewModel) {
    SectionCard {
        Text(stringResource(R.string.laho_peq_source), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = state.source == PeqSource.GRAPHIC_EQ,
                onClick = { viewModel.setSource(PeqSource.GRAPHIC_EQ) },
                label = { Text(stringResource(R.string.laho_peq_source_graphic)) },
            )
            FilterChip(
                selected = state.source == PeqSource.PRESET,
                enabled = state.presets.isNotEmpty(),
                onClick = { viewModel.setSource(PeqSource.PRESET) },
                label = { Text(stringResource(R.string.laho_peq_source_preset)) },
            )
        }
        Text(
            stringResource(
                if (state.source == PeqSource.GRAPHIC_EQ) R.string.laho_peq_source_graphic_hint
                else R.string.laho_peq_source_preset_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetSection(
    state: PeqPresetState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Text(stringResource(R.string.laho_peq_presets), style = MaterialTheme.typography.titleMedium)
        if (state.presets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                state.presets.forEach { p ->
                    FilterChip(selected = p.name == state.activeName, onClick = { onSelect(p.name) }, label = { Text(p.name) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            OutlinedButton(onClick = onNew) { Text(stringResource(R.string.laho_peq_new)) }
            OutlinedButton(onClick = onImport) { Text(stringResource(R.string.laho_peq_import)) }
            if (state.activePreset != null) {
                TextButton(onClick = onRename) { Text(stringResource(R.string.laho_peq_rename)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.laho_peq_delete)) }
            }
        }
    }
}

@Composable
private fun GraphSection(config: PeqConfig) {
    SectionCard {
        PeqResponseGraph(config, contentDescription = stringResource(R.string.laho_peq_graph_desc))
        // Axis labels placed at their true log positions.
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            listOf(100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k").forEach { (hz, text) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.offset(x = maxWidth * PeqResponse.fractionOf(hz).toFloat() - 8.dp),
                )
            }
            Text("20", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterStart))
            Text("20k", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterEnd))
        }
        val peak = remember(config) {
            PeqResponse.curveDb(config, PeqResponse.logFrequencies(256)).maxOrNull() ?: 0.0
        }
        Text(
            stringResource(R.string.laho_peq_peak, fmt(peak, 1)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (peak > 0.05) {
            Text(
                stringResource(R.string.laho_peq_clip_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MasterSection(config: PeqConfig, viewModel: PeqEditorViewModel) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.laho_peq_enabled), modifier = Modifier.weight(1f))
            Switch(checked = config.enabled, onCheckedChange = viewModel::setEnabled)
        }
        ValueSlider(
            label = stringResource(R.string.laho_peq_preamp),
            valueText = "${fmt(config.preampDb, 1)} dB",
            value = config.preampDb.toFloat().coerceIn(PREAMP_MIN, PREAMP_MAX),
            range = PREAMP_MIN..PREAMP_MAX,
            onChange = { viewModel.setPreamp((it * 10).roundToInt() / 10.0) },
            exactInitial = fmt(config.preampDb, 1),
            onExact = { v -> v.toDoubleOrNull()?.let { viewModel.setPreamp(it.coerceIn(-60.0, 24.0)) } },
        )
        TextButton(onClick = viewModel::autoPreamp) { Text(stringResource(R.string.laho_peq_auto_preamp)) }
    }
}

@Composable
private fun BandCard(index: Int, band: PeqBand, onChange: (PeqBand) -> Unit, onRemove: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.laho_peq_band, index + 1),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
            IconButton(onClick = onRemove) {
                Icon(RhythmIcons.Delete, contentDescription = stringResource(R.string.laho_peq_remove_band))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val shown = listOf(FilterType.PEAKING, FilterType.LOW_SHELF, FilterType.HIGH_SHELF) +
                listOf(FilterType.LOW_PASS, FilterType.HIGH_PASS).filter { it == band.type }
            shown.forEach { t ->
                FilterChip(
                    selected = band.type == t,
                    onClick = { onChange(band.copy(type = t)) },
                    label = { Text(typeCode(t)) },
                )
            }
        }
        ValueSlider(
            label = stringResource(R.string.laho_peq_frequency),
            valueText = formatHz(band.frequencyHz),
            value = PeqResponse.fractionOf(band.frequencyHz).toFloat(),
            range = 0f..1f,
            onChange = { onChange(band.copy(frequencyHz = roundHz(PeqResponse.frequencyAt(it.toDouble())))) },
            exactInitial = band.frequencyHz.roundToInt().toString(),
            onExact = { v -> v.toDoubleOrNull()?.let { onChange(band.copy(frequencyHz = it.coerceIn(10.0, 24_000.0))) } },
        )
        val hasGain = band.type == FilterType.PEAKING || band.type == FilterType.LOW_SHELF || band.type == FilterType.HIGH_SHELF
        ValueSlider(
            label = stringResource(R.string.laho_peq_gain),
            valueText = "${fmt(band.gainDb, 1)} dB",
            value = band.gainDb.toFloat().coerceIn(-GAIN_RANGE, GAIN_RANGE),
            range = -GAIN_RANGE..GAIN_RANGE,
            enabled = hasGain,
            onChange = { onChange(band.copy(gainDb = (it * 10).roundToInt() / 10.0)) },
            exactInitial = fmt(band.gainDb, 1),
            onExact = { v -> v.toDoubleOrNull()?.let { onChange(band.copy(gainDb = it.coerceIn(-30.0, 30.0))) } },
        )
        ValueSlider(
            label = stringResource(R.string.laho_peq_q),
            valueText = fmt(band.q, 2),
            value = (ln(band.q.coerceIn(Q_MIN, Q_MAX) / Q_MIN) / ln(Q_MAX / Q_MIN)).toFloat(),
            range = 0f..1f,
            onChange = {
                val q = Q_MIN * exp(ln(Q_MAX / Q_MIN) * it)
                onChange(band.copy(q = (q * 100).roundToInt() / 100.0))
            },
            exactInitial = fmt(band.q, 2),
            onExact = { v -> v.toDoubleOrNull()?.let { onChange(band.copy(q = it.coerceIn(0.05, 30.0))) } },
        )
    }
}

/** Rounds to 1 Hz below 1 kHz and to 10 Hz above, so the slider gives tidy values. */
private fun roundHz(hz: Double): Double = if (hz < 1000) hz.roundToInt().toDouble() else (hz / 10).roundToInt() * 10.0

@Composable
private fun ValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    exactInitial: String,
    onExact: (String) -> Unit,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }, enabled = enabled) { Text(valueText) }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
    if (editing) {
        TextInputDialog(
            title = label,
            initial = exactInitial,
            keyboardType = KeyboardType.Decimal,
            onDismiss = { editing = false },
            onConfirm = { onExact(it.replace(',', '.')); editing = false },
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.laho_peq_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.laho_peq_cancel)) } },
    )
}
