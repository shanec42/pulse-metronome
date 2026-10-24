package com.austinlinux.pulsemetronome.ui.settings

import com.austinlinux.pulsemetronome.ui.theme.LIST_END_GAP
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import com.austinlinux.pulsemetronome.engine.MonitorSignalLoss
import com.austinlinux.pulsemetronome.engine.PacedSignalLoss
import androidx.compose.animation.core.tween
import com.austinlinux.pulsemetronome.engine.DescentPattern
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import android.content.Intent
import androidx.core.content.FileProvider
import com.austinlinux.pulsemetronome.debug.CAPTURE_SHARE_DIR
import com.austinlinux.pulsemetronome.debug.DebugCapture
import com.austinlinux.pulsemetronome.debug.captureSummary
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.alarm.AlarmType
import com.austinlinux.pulsemetronome.ui.theme.ConfirmDialog
import com.austinlinux.pulsemetronome.ui.theme.MessageBar
import com.austinlinux.pulsemetronome.ui.theme.PmSwitch
import com.austinlinux.pulsemetronome.ui.theme.MonoLabel
import com.austinlinux.pulsemetronome.ui.theme.NumberStepper
import com.austinlinux.pulsemetronome.ui.theme.PmCard
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts
import com.austinlinux.pulsemetronome.ui.theme.QuietTextButton
import com.austinlinux.pulsemetronome.ui.theme.RowDivider
import com.austinlinux.pulsemetronome.ui.theme.ScreenTitle

/**
 * Compose layout: numeric inputs for max offset (required, >= 1, default 2 - pre-filled, not
 * blank/optional per Task 19's brief) and static fallback starting BPM, plus a 4-way alarm
 * type selector, all showing validation errors inline. Values persist via SettingsViewModel
 * on every valid change (no explicit Save button - mirrors DataStore's reactive nature).
 *
 * Compose layout itself is not unit-tested in this stack - see Task 18's note; pure logic
 * (validateSettings) is tested in SettingsValidationTest instead. Manual on-device
 * verification only for this file.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val errors = uiState.validation.errors
    val offsetError = "Max offset is required and must be at least 1 BPM"

    var confirmDelete by remember { mutableStateOf(false) }
    var showDeleted by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportMessage) {
        if (exportMessage != null) { delay(1800); exportMessage = null }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult  // cancelled
        scope.launch {
            val ok = runCatching {
                val csv = viewModel.historyCsvText()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(csv.toByteArray()) }
                }
            }.isSuccess
            exportMessage = if (ok) "History exported" else "Couldn't export history"
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val debugAvailable = remember { DebugCapture.isAvailable(context) }
    LaunchedEffect(showDeleted) {
        if (showDeleted) { delay(1800); showDeleted = false }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete all sessions?", "This can't be undone.", "Delete",
            onConfirm = { confirmDelete = false; viewModel.deleteAllHistory(); showDeleted = true },
            onCancel = { confirmDelete = false }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            "Clear the debug capture?", "This deletes every saved capture file.", "Clear",
            onConfirm = {
                confirmClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { DebugCapture.clear() }
                    refreshKey++
                    exportMessage = "Capture cleared"
                }
            },
            onCancel = { confirmClear = false }
        )
    }

    Box(Modifier.fillMaxSize().background(PmColors.Background)) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp)) { QuietTextButton("← Back", onBack) }
        ScreenTitle("Settings", Modifier.padding(start = 22.dp, top = 2.dp, end = 22.dp, bottom = 4.dp))
        Text("Changes save automatically.", color = PmColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 18.dp))

        PmCard(Modifier.padding(horizontal = 16.dp)) {
            SettingRow(
                "Max offset (BPM)",
                "With a pulse meter: the most the metronome sits below your pulse. Without one: the most it drops per minute.",
                uiState.maxOffset, error = if (offsetError in errors) offsetError else null,
                min = 1, max = 20, onValue = viewModel::onMaxOffsetChanged
            )
            RowDivider()
            DescentPatternRow(uiState.descentPattern, viewModel::onDescentPatternChanged)
        }

        MonoLabel("When time runs out", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
        PmCard(Modifier.padding(horizontal = 16.dp)) {
            AlarmType.values().forEachIndexed { i, type ->
                if (i > 0) RowDivider()
                val (title, desc) = alarmCopy(type)
                val selected = uiState.alarmType == type
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp)
                        .clickable { viewModel.onAlarmTypeChanged(type) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .border(2.dp, if (selected) PmColors.Red else PmColors.Text.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(PmColors.Red)) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                        Text(desc, color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }

        MonoLabel("When the pulse is lost (paced sessions)", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
        PmCard(Modifier.padding(horizontal = 16.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SegmentedChoice(
                    listOf("Hold at the last value", "Continue the descent"),
                    PacedSignalLoss.values().indexOf(uiState.pacedSignalLoss)
                ) { viewModel.onPacedSignalLossChanged(PacedSignalLoss.values()[it]) }
                Text(
                    "Used when a pulse meter drops out during a paced session. \"Continue\" keeps coming down on the planned schedule.",
                    color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        MonoLabel("When the pulse is lost (Monitor mode)", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
        PmCard(Modifier.padding(horizontal = 16.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SegmentedChoice(
                    listOf("Hold at the last value", "Stop the metronome"),
                    MonitorSignalLoss.values().indexOf(uiState.monitorSignalLoss)
                ) { viewModel.onMonitorSignalLossChanged(MonitorSignalLoss.values()[it]) }
                Text(
                    "Only used in Monitor mode. \"Stop\" goes silent until the pulse returns.",
                    color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        MonoLabel("History", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
        PmCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (debugAvailable) 0.dp else LIST_END_GAP)) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = uiState.historyEnabled, role = Role.Switch, onValueChange = viewModel::onHistoryEnabledChanged)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Save session history", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                    Text("When off, sessions still show their summary but aren't kept.", color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                PmSwitch(uiState.historyEnabled, viewModel::onHistoryEnabledChanged)
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                    .clickable(role = Role.Button) {
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportLauncher.launch("pulse-metronome-history-$day.csv")
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Export history (CSV)", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                    .clickable(role = Role.Button) { confirmDelete = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Delete all history", color = PmColors.RedText, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
            }
        }

        if (debugAvailable) {
            val summary by produceState("", refreshKey) {
                while (true) {
                    value = withContext(Dispatchers.IO) {
                        val f = DebugCapture.files()
                        captureSummary(f.size, f.sumOf { it.length() })
                    }
                    delay(2000)
                }
            }
            MonoLabel("Debug capture", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
            PmCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = LIST_END_GAP)) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = uiState.debugCapture, role = Role.Switch, onValueChange = viewModel::onDebugCaptureChanged)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Record debug capture", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                        Text(summary, color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    PmSwitch(uiState.debugCapture, viewModel::onDebugCaptureChanged)
                }
                RowDivider()
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                        .clickable(role = Role.Button) {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                        val dir = File(context.cacheDir, CAPTURE_SHARE_DIR).also { it.mkdirs() }
                                        val dest = File(dir, "pulse-metronome-capture-$day.zip")
                                        if (DebugCapture.zipTo(dest)) {
                                            FileProvider.getUriForFile(context, context.packageName + ".debugcapture", dest)
                                        } else null
                                    }
                                }
                                val uri = result.getOrNull()
                                if (result.isFailure) {
                                    exportMessage = "Couldn't share capture"
                                } else if (uri == null) {
                                    exportMessage = "Nothing to share yet"
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).setType("application/zip")
                                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                "Share capture"
                                            )
                                        )
                                    }.onFailure { exportMessage = "Couldn't share capture" }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Share capture", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                }
                RowDivider()
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                        .clickable(role = Role.Button) { confirmClear = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Clear capture", color = PmColors.RedText, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                        Text("Deletes all current and old captures on this phone.", color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
    if (showDeleted) MessageBar("All sessions deleted", Modifier.align(Alignment.BottomCenter))
    exportMessage?.let { MessageBar(it, Modifier.align(Alignment.BottomCenter)) }
    }
}

private fun alarmCopy(type: AlarmType): Pair<String, String> = when (type) {
    AlarmType.SOUND -> "Sound" to "Looping alarm tone, audible even when media is muted"
    AlarmType.VIBRATE_ONLY -> "Vibrate only" to "Repeating vibration, 500 ms on / 500 ms off"
    AlarmType.SOUND_AND_VIBRATE -> "Sound and vibrate" to "Alarm tone plus vibration"
    AlarmType.SILENT -> "Silent" to "Nothing plays and no notification is posted"
}

@Composable
private fun SettingRow(
    label: String, description: String, value: Int?, error: String?,
    min: Int, max: Int, onValue: (Int?) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = PmColors.Text, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
            NumberStepper(
                value = value, isError = error != null, step = 1, onValueChange = onValue,
                onBump = { d -> onValue(((value ?: min) + d).coerceIn(min, max)) }
            )
        }
        Text(description, color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(end = 8.dp))
        if (error != null) Text(error, color = PmColors.RedText, fontSize = 12.sp)
    }
}

/** Descent pattern row: title and description above a [SegmentedChoice]. */
@Composable
private fun DescentPatternRow(selected: DescentPattern, onSelect: (DescentPattern) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Descent pattern", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
            Text("Bell curve or Linear. Used when no pulse meter is connected.", color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        SegmentedChoice(
            listOf("Bell curve", "Linear"), DescentPattern.values().indexOf(selected)
        ) { onSelect(DescentPattern.values()[it]) }
    }
}

/**
 * Recessed track with one raised pill that slides (320 ms, ease-out) to the selected segment;
 * label colours cross-fade. Equal-width segments, so the pill offset is index * segment width.
 */
@Composable
private fun SegmentedChoice(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val pillShape = RoundedCornerShape(22.dp)
    val gap = 4.dp
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(PmColors.Background)
            .border(1.dp, PmColors.Hairline, shape)
            .padding(4.dp)
    ) {
        val segment = (maxWidth - gap * (options.size - 1)) / options.size
        val pillX by animateDpAsState((segment + gap) * selectedIndex, tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "pillX")
        Box(
            Modifier.offset(x = pillX).width(segment).height(48.dp)
                .shadow(2.dp, pillShape)
                .clip(pillShape)
                .background(PmColors.Surface2)
                .border(1.dp, PmColors.Hairline, pillShape)
        )
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, label ->
                val isSelected = i == selectedIndex
                val fg by animateColorAsState(if (isSelected) PmColors.Text else PmColors.Muted, tween(250), label = "fg")
                Box(
                    Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(pillShape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(i) })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, color = fg, textAlign = TextAlign.Center, lineHeight = 16.sp,
                        style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    )
                }
            }
        }
    }
}
