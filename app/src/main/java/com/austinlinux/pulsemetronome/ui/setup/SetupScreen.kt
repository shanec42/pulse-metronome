package com.austinlinux.pulsemetronome.ui.setup

import androidx.compose.ui.graphics.toArgb
import com.austinlinux.pulsemetronome.ui.theme.TopNoticeBar
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.austinlinux.pulsemetronome.alarm.ACTION_DISMISS_ALARM
import com.austinlinux.pulsemetronome.service.SessionStateBus
import com.austinlinux.pulsemetronome.ui.theme.AlarmDismissBar
import com.austinlinux.pulsemetronome.ui.theme.pinnedButtonBottomGap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.ble.BleModule
import com.austinlinux.pulsemetronome.ble.DeviceCandidate
import com.austinlinux.pulsemetronome.ble.ScanOutcome
import com.austinlinux.pulsemetronome.ble.protocol.isBatteryLow
import com.austinlinux.pulsemetronome.engine.DescentPattern
import com.austinlinux.pulsemetronome.engine.StartMode
import com.austinlinux.pulsemetronome.ui.theme.EcgMode
import com.austinlinux.pulsemetronome.ui.theme.MetronomeEcg
import com.austinlinux.pulsemetronome.ui.theme.MonoLabel
import com.austinlinux.pulsemetronome.ui.theme.NumberStepper
import com.austinlinux.pulsemetronome.ui.theme.OutlinePill
import com.austinlinux.pulsemetronome.ui.theme.PillButton
import com.austinlinux.pulsemetronome.ui.theme.PmCard
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts
import com.austinlinux.pulsemetronome.ui.theme.QuietTextButton
import com.austinlinux.pulsemetronome.ui.theme.RowDivider
import com.austinlinux.pulsemetronome.ui.theme.RowTitle

/**
 * Compose layout: connection card (or a tappable Bluetooth-permission notice when permission is
 * missing), Target BPM / Total duration / Ramp duration inputs, a "Wait for pulse before
 * starting" switch and a single pinned Start button whose label and rules depend on the mode
 * (resolveStartMode / setupDisplay / startEnabled):
 * - Wait for pulse on: "Start when pulse is steady" (WHEN_PULSE_DETECTED); needs Bluetooth permission,
 *   enabled with no meter connected yet (the session waits and auto-reconnects).
 * - Wait off, meter connected: "Start" (NOW); starts from the live reading.
 * - Wait off, no meter: a Starting BPM row appears and the button reads "Start without meter"
 *   (NO_PULSE); no Bluetooth permission needed. Starting BPM equal to or below Target only warns.
 *
 * Compose layout itself is not unit-tested; pure logic lives in SetupViewModel.kt and is covered
 * by SetupValidationTest. Manual on-device verification only for this file.
 */
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    bleModule: BleModule,
    startingBpm: Int?,
    onStartingBpmChanged: (Int?) -> Unit,
    maxOffset: Int,
    descentPattern: DescentPattern,
    bleGranted: Boolean = true,
    onPermissionNoticeTapped: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToInfo: () -> Unit,
    onNavigateToFaq: () -> Unit,
    onStartSession: (StartMode) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by bleModule.connectionState.collectAsState()
    // BleModule clears this when the scan window closes or fails; uiState.scanning can stick on early-return paths.
    val scanInProgressSince by bleModule.scanInProgressSince.collectAsState()
    val deviceName by bleModule.connectedDeviceName.collectAsState()
    val battery by bleModule.batteryPercent.collectAsState()
    val alarm by SessionStateBus.alarm.collectAsState()
    val context = LocalContext.current

    val validation = uiState.validation
    val display = setupDisplay(uiState.waitForPulse, connectionState, uiState.monitor)
    val canStart = startEnabled(
        validation.isValid, uiState.waitForPulse, connectionState, bleGranted,
        scanInProgressSince != null, startingBpm, uiState.targetBpm,
        uiState.monitor, uiState.totalDurationMin
    )
    val showNote = !uiState.monitor && startingBpm != null && showMayNotReachNote(
        uiState.waitForPulse, connectionState, startingBpm, uiState.targetBpm,
        uiState.totalDurationMin, uiState.rampDurationMin, maxOffset, descentPattern
    )
    // Steady-BPM warning only: Start stays enabled (user ruling 2026-10-10).
    val startingEqualsTarget = display.showStartingBpm && startingBpm != null && uiState.targetBpm != null &&
        isStartNowPacingDisabled(startingBpm, uiState.targetBpm!!)

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(PmColors.Background)) {
        // Header sits outside the scroll so Info / FAQ / History / Settings stay reachable while scrolled.
        // Links go beside the two-line title only if "Metronome" plus all four links measure as fitting
        // (respects the user's font scale); otherwise they drop to their own row so nothing wraps or truncates.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val sideTitleStyle = TextStyle(fontFamily = PmFonts.Display, fontSize = 23.sp, lineHeight = 24.sp)
        val linkStyle = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val needed = remember(density.density, density.fontScale) {
                val titlePx = measurer.measure("Metronome", sideTitleStyle, maxLines = 1).size.width
                val linksPx = listOf("Info", "FAQ", "History", "Settings").sumOf {
                    measurer.measure(it, linkStyle, maxLines = 1).size.width
                }
                with(density) { titlePx.toDp() + linksPx.toDp() + (4 * 2 * 8).dp + 22.dp + 8.dp + 4.dp }
            }
            if (needed <= maxWidth) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Pulse\nMetronome", color = PmColors.Text, modifier = Modifier.weight(1f),
                        style = sideTitleStyle
                    )
                    QuietTextButton("Info", onNavigateToInfo, horizontalPadding = 8.dp)
                    QuietTextButton("FAQ", onNavigateToFaq, horizontalPadding = 8.dp)
                    QuietTextButton("History", onNavigateToHistory, horizontalPadding = 8.dp)
                    QuietTextButton("Settings", onNavigateToSettings, horizontalPadding = 8.dp)
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Pulse Metronome", color = PmColors.Text, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp),
                        style = TextStyle(fontFamily = PmFonts.Display, fontSize = 28.sp, lineHeight = 28.sp)
                    )
                    // start = 14dp + 8dp button padding lines the first link's text up with the title at 22dp.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuietTextButton("Info", onNavigateToInfo, horizontalPadding = 8.dp)
                        QuietTextButton("FAQ", onNavigateToFaq, horizontalPadding = 8.dp)
                        QuietTextButton("History", onNavigateToHistory, horizontalPadding = 8.dp)
                        QuietTextButton("Settings", onNavigateToSettings, horizontalPadding = 8.dp)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {

            MetronomeEcg(
                mode = if (connectionState) EcgMode.TRACE else EcgMode.FLAT,
                bpm = IDLE_DISPLAY_BPM,
                height = 220.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)
            )

            if (bleGranted) {
                ConnectionCard(
                    connected = connectionState,
                    deviceName = deviceName,
                    battery = battery,
                    // BleModule's own window state, which always clears; the old uiState flag stuck
                    // "Scanning…" (and disabled Rescan) when a tap while connected started no scan.
                    scanning = scanInProgressSince != null,
                    onScan = { viewModel.onScanRequested() }
                )
            } else {
                PermissionNotice(onPermissionNoticeTapped)
            }
            if (uiState.scanOutcome is ScanOutcome.NoneFound) {
                Text(
                    "No pulse oximeter found — make sure it's powered on and in range, then try again",
                    color = PmColors.RedText, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
                )
            }

            MonoLabel("Session", Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp))
            // Hidden Target/Ramp fields must not show errors in Monitor mode.
            val errors = if (uiState.monitor) validation.errors.filter { it.startsWith("Total duration") } else validation.errors
            PmCard(Modifier.padding(horizontal = 16.dp)) {
                if (display.showStartingBpm) {
                    SessionRow(
                        "Starting BPM", "30–200", startingBpm,
                        isError = startingBpm == null || startingBpm !in 30..200 || startingEqualsTarget,
                        step = 1, min = 30, max = 200,
                        onValue = onStartingBpmChanged
                    )
                    RowDivider()
                }
                if (display.showTargetAndRamp) {
                SessionRow(
                    "Target BPM", "30–200", uiState.targetBpm,
                    isError = "Target BPM must be 30-200" in errors,
                    step = 1, min = 30, max = 200,
                                        onValue = viewModel::onTargetBpmChanged
                )
                RowDivider()
                }
                SessionRow(
                    "Total duration", "minutes", uiState.totalDurationMin,
                    isError = "Total duration must be > 0" in errors,
                    step = 5, min = 1, max = 600,
                                        onValue = viewModel::onTotalDurationChanged
                )
                if (display.showTargetAndRamp) {
                RowDivider()
                SessionRow(
                    "Ramp duration",
                    if (uiState.rampEditedByUser) "minutes" else "minutes · follows total",
                    uiState.rampDurationMin,
                    isError = "Ramp duration must be > 0" in errors ||
                        "Ramp duration cannot exceed total duration" in errors,
                    step = 5, min = 1, max = rampStepperMax(uiState.totalDurationMin),
                    onValue = viewModel::onRampDurationChanged,
                    onBump = viewModel::onRampBumped
                )
                }
            }

            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                errors.forEach { Text(it, color = PmColors.RedText, fontSize = 13.sp) }
                if (showNote) {
                    Text(
                        "With current settings, the metronome may not reach your target BPM this session — that's expected sometimes",
                        color = PmColors.Muted, fontSize = 13.sp, lineHeight = 19.sp
                    )
                }
            }

            SwitchRow(
                "Monitor mode", "The metronome keeps pace with your pulse. No target.",
                uiState.monitor, viewModel::onMonitorChanged,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (display.showWaitForPulse) 0.dp else 20.dp)
            )
            if (display.showWaitForPulse) {
                SwitchRow(
                    "Wait for pulse before starting", "Starts once the pulse is detected.",
                    uiState.waitForPulse, viewModel::onWaitForPulseChanged,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp)
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = pinnedButtonBottomGap()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // A ringing alarm with no Summary on screen (e.g. after the app was reopened) must still be stoppable here.
            if (alarm != null) {
                AlarmDismissBar(onDismiss = {
                    context.sendBroadcast(Intent(ACTION_DISMISS_ALARM).setPackage(context.packageName))
                    SessionStateBus.clearAlarm()
                })
            }
            PillButton(
                display.startLabel,
                onClick = {
                    onStartSession(resolveStartMode(uiState.waitForPulse, connectionState, uiState.monitor))
                },
                enabled = canStart
            )
            if (startingEqualsTarget) {
                Text(
                    "Starting BPM is equal to Target BPM",
                    color = PmColors.RedText, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = uiState.restartHint,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut()
    ) {
        TopNoticeBar("Meter not showing up? It may need a restart: switch it off and on, then scan again.")
    }
    }

    val scanOutcome = uiState.scanOutcome
    if (scanOutcome is ScanOutcome.MultipleFound) {
        DevicePickerSheet(
            candidates = scanOutcome.candidates,
            onPick = { viewModel.onDevicePicked(it) },
            onDismiss = { viewModel.dismissDevicePicker() }
        )
    }
}

// Decorative resting tempo for the Setup screen's metronome animation while connected; it is
// not a reading and is never persisted or used for pacing.
private const val IDLE_DISPLAY_BPM = 66.0

@Composable
private fun ConnectionCard(
    connected: Boolean, deviceName: String?, battery: Int?, scanning: Boolean, onScan: () -> Unit
) {
    val dot = when { connected -> PmColors.Green; scanning -> PmColors.Brass; else -> PmColors.Dim }
    Row(
        Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(PmColors.Surface)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(17.dp).clip(CircleShape).background(dot.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(9.dp).clip(CircleShape).background(dot)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                when { connected -> "Connected"; scanning -> "Scanning…"; else -> "Not connected" },
                color = PmColors.Text,
                style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            )
            val lowBattery = connected && isBatteryLow(battery)
            Text(
                buildAnnotatedString {
                    append(
                        when {
                            connected -> deviceName ?: "Pulse oximeter"
                            scanning -> "Looking for pulse oximeters"
                            else -> "Pulse oximeter"
                        }
                    )
                    if (lowBattery) {
                        append(" · ")
                        withStyle(SpanStyle(color = PmColors.RedText, fontWeight = FontWeight.Medium)) { append("Battery low") }
                    }
                },
                color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        OutlinePill(
            if (connected) "Rescan" else "Scan / Connect",
            onClick = onScan,
            enabled = !scanning
        )
    }
}

/** Outlined, tappable notice shown instead of the connection card when Bluetooth permission is missing (design skin). */
@Composable
private fun PermissionNotice(onTap: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, PmColors.Hairline, RoundedCornerShape(24.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        Box(Modifier.size(6.dp).border(1.dp, PmColors.Muted, CircleShape))
        Text(
            buildAnnotatedString {
                append("Bluetooth permission not granted · ")
                withStyle(SpanStyle(color = PmColors.Brass, fontWeight = FontWeight.Medium)) { append("tap to allow") }
            },
            color = PmColors.Muted, fontSize = 13.sp, lineHeight = 18.sp
        )
    }
}

/** One stepper row of the Session card; the −/+ buttons clamp to [min]..[max]. */
@Composable
private fun SessionRow(
    label: String, hint: String, value: Int?, isError: Boolean,
    step: Int, min: Int, max: Int, onValue: (Int?) -> Unit,
    onBump: ((Int) -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RowTitle(label, hint, modifier = Modifier.weight(1f))
        NumberStepper(
            value = value, isError = isError, step = step,
            onValueChange = onValue,
            onBump = onBump ?: { d -> onValue(((value ?: min) + d).coerceIn(min, max)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePickerSheet(
    candidates: List<DeviceCandidate>,
    onPick: (DeviceCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PmColors.Surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        // The sheet is its own window: on 3-button navigation Android drew a light, contrast-scrimmed
        // bar under it. Design: the bar takes the sheet colour with light buttons.
        val sheetView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (sheetView as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: (sheetView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (window != null) {
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION") window.navigationBarColor = PmColors.Surface.toArgb()
                androidx.core.view.WindowCompat.getInsetsController(window, sheetView).isAppearanceLightNavigationBars = false
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (candidates.any { it.needsRestart }) "Choose a meter" else "Multiple devices found", color = PmColors.Text,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                style = TextStyle(fontFamily = PmFonts.Display, fontSize = 26.sp)
            )
            candidates.forEach { candidate ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
                        .background(PmColors.Surface2).clickable { onPick(candidate) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                candidate.name ?: candidate.address, color = PmColors.Text, maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false),
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = if (candidate.name != null)
                                    TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                else
                                    TextStyle(fontFamily = PmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            )
                            if (!candidate.tested) {
                                Text(
                                    "UNTESTED", color = PmColors.Muted,
                                    modifier = Modifier.border(1.dp, PmColors.Muted.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = TextStyle(fontFamily = PmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp)
                                )
                            }
                        }
                        if (candidate.name != null) {
                            Text(candidate.address, color = PmColors.Dim, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp))
                        }
                        if (candidate.needsRestart) {
                            Text("Switch off and on to reconnect", color = PmColors.Brass, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                    if (!candidate.needsRestart) Text(signalLabel(candidate.rssi), color = PmColors.Muted, fontSize = 12.sp)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) { Text("Cancel", color = PmColors.Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
        }
    }
}

/** Buckets a raw RSSI (dBm) into the Strong/Fair/Weak labels shown in the device picker. */
fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -75 -> "Fair"
    else -> "Weak"
}

/** A Setup toggle row: label and subtitle on the left, switch on the right; the whole row toggles. */
@Composable
private fun SwitchRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(PmColors.Surface)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label, color = PmColors.Text,
                style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            )
            Text(subtitle, color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PmColors.Red, checkedThumbColor = PmColors.Text,
                checkedBorderColor = PmColors.Red,
                uncheckedTrackColor = PmColors.Surface2, uncheckedThumbColor = PmColors.Text,
                uncheckedBorderColor = PmColors.Surface2
            )
        )
    }
}
