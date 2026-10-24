package com.austinlinux.pulsemetronome.ui.session

import com.austinlinux.pulsemetronome.ui.theme.pinnedButtonBottomGap
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.LineBreak
import com.austinlinux.pulsemetronome.ui.theme.SmallPrint
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.engine.SessionState
import com.austinlinux.pulsemetronome.engine.syncWindowMin
import com.austinlinux.pulsemetronome.service.SessionForegroundService
import com.austinlinux.pulsemetronome.service.SessionStateBus
import com.austinlinux.pulsemetronome.ui.theme.EcgMode
import com.austinlinux.pulsemetronome.ui.theme.MetronomeEcg
import com.austinlinux.pulsemetronome.ui.theme.MonoLabel
import com.austinlinux.pulsemetronome.ui.theme.PillButton
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts
import com.austinlinux.pulsemetronome.ui.theme.ScanFillPill
import com.austinlinux.pulsemetronome.ui.theme.StatusChip
import com.austinlinux.pulsemetronome.ble.protocol.isBatteryLow

/**
 * Compose layout: while AWAITING_START (only reachable under StartMode.WHEN_PULSE_DETECTED,
 * since NOW resolves synchronously), shows "Connecting…" (not yet BLE-connected),
 * "Verifying pulse (n/3)" (connected, verification streak in progress), or "Waiting for
 * pulse…" (connected, no streak progress yet) so the screen doesn't look hung during BLE
 * connect + the 3-reading verification (bell-curve pacing redesign). While RUNNING, shows
 * live pulse, metronome BPM, offset, elapsed/remaining time, a beat indicator, and a
 * signal-lost indicator. Stop is visible in both phases and sends
 * SessionForegroundService.newStopIntent(context) - this screen has no direct reference to
 * the service-owned SessionEngine.
 *
 * Compose layout itself is not unit-tested in this stack - see Task 18's note; pure logic
 * (formatElapsed) is tested in TimeFormattingTest instead. Manual on-device verification only
 * for this file.
 */
private const val STOP_DIALOG_FADE_MS = 250L

@Composable
fun LiveSessionScreen(viewModel: LiveSessionViewModel, onSessionEnded: () -> Unit, onShowSummary: (Long) -> Unit) {
    val state by viewModel.state.collectAsState()
    val connected by viewModel.connectionState.collectAsState()
    val battery by viewModel.batteryPercent.collectAsState()
    val scanSince by viewModel.scanInProgressSince.collectAsState()
    val connectingSince by viewModel.connectingSince.collectAsState()
    val context = LocalContext.current
    val current = state

    val endedId by viewModel.endedSessionId.collectAsState()

    // Every ending that produced a session row opens its Summary. Only a session stopped while
    // still waiting for a pulse (no row) shows the brief "Session ended" text and returns to Setup.
    // The service publishes endedSessionId BEFORE clearing state, so once state is null the
    // absence of an id is final. sawSession keeps the first frames (state not yet published)
    // from being mistaken for an ended session.
    // Saveable so a rotation during the 1.3 s flash doesn't reset them and strand a blank screen.
    var sawSession by rememberSaveable { mutableStateOf(false) }
    var left by rememberSaveable { mutableStateOf(false) }
    val ended = current == null || current.phase == SessionState.Phase.ENDED
    LaunchedEffect(current) { if (current != null) sawSession = true }
    LaunchedEffect(endedId) {
        val id = endedId ?: return@LaunchedEffect
        if (left) return@LaunchedEffect
        left = true
        viewModel.consumeEndedSessionId()
        onShowSummary(id)
    }
    LaunchedEffect(current == null, sawSession) {
        if (current == null && sawSession && viewModel.endedSessionId.value == null) {
            delay(1300)
            if (!left) { left = true; onSessionEnded() }
        }
    }

    if (ended) {
        Box(Modifier.fillMaxSize().background(PmColors.Background), contentAlignment = Alignment.Center) {
            if (sawSession && current == null && endedId == null && !left) {
                Text("Session ended", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Display, fontSize = 36.sp))
            }
        }
        return
    }
    current!!

    val stopSession = { context.startService(SessionForegroundService.newStopIntent(context)); Unit }
    // There's only ever one session, so system Back mid-session would just strand it behind
    // Setup; Back asks to stop it instead.
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val dialogScope = rememberCoroutineScope()
    BackHandler { confirmStop = true }
    if (confirmStop) {
        StopSessionDialog(
            onKeepGoing = { confirmStop = false },
            // Let the dialog finish fading out first, so it doesn't overlap the "Session ended" text.
            onStop = { confirmStop = false; dialogScope.launch { delay(STOP_DIALOG_FADE_MS); stopSession() } }
        )
    }

    val running = current.phase == SessionState.Phase.RUNNING
    val phaseLabel = when {
        !running -> "Starting"
        current.monitor -> "Monitor"
        current.targetReached -> "At target"
        current.runningElapsedMs < syncWindowMin(current.totalDurationMs / 60_000.0) * 60_000.0 -> "Syncing"
        else -> "Pacing down"
    }
    val ecgMode = when {
        running && current.metronomeBpm != null -> EcgMode.TRACE
        else -> EcgMode.SCAN
    }

    Column(Modifier.fillMaxSize().background(PmColors.Background).padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(PmColors.Red))
            MonoLabel(phaseLabel)
        }

        MetronomeEcg(
            mode = ecgMode,
            bpm = current.metronomeBpm ?: 0.0,
            height = 330.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )

        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            SmallPrint(
                "Illustration only — not a recording of your heart rhythm. Not for medical use.",
                centered = true,
                lineBreak = LineBreak.Heading,
                modifier = Modifier.widthIn(max = 290.dp)
                    .drawBehind { drawLine(PmColors.Text.copy(alpha = 0.05f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(vertical = 8.dp)
            )
        }

        if (!running) {
            // Only reachable under StartMode.WHEN_PULSE_DETECTED (NOW resolves synchronously):
            // distinguishes BLE connecting from the 3-reading verification so the screen
            // never looks hung.
            val label = if (!connected) "Connecting…"
            else if (current.verificationStreakCount > 0) "Verifying pulse (${current.verificationStreakCount}/3)"
            else "Waiting for pulse…"
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
            ) {
                Text(
                    label, color = PmColors.Text, textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = PmFonts.Display, fontSize = 34.sp, lineHeight = 37.sp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 1..3) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                if (current.verificationStreakCount >= i) PmColors.Red
                                else PmColors.Text.copy(alpha = 0.18f)
                            )
                        )
                    }
                }
                Text(
                    "The metronome stays silent until three steady readings agree.",
                    color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.width(260.dp)
                )
                if (!connected) {
                    ScanFillPill(scanningSince = scanSince, connectingSince = connectingSince, onClick = viewModel::onScanNow, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.Bottom)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("Metronome BPM", current.metronomeBpm?.let { "%.0f".format(it) } ?: "—", 64, PmColors.Text, if (current.noPulse) Modifier else Modifier.weight(1.3f))
                    if (current.noPulse) {
                        // Bottom-aligned like the "Metronome BPM" label (the row uses Alignment.Bottom); 1dp matches the design.
                        Text(
                            "No pulse meter · following the schedule",
                            color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f).padding(bottom = 1.dp)
                        )
                    } else {
                        Metric(
                            "Pulse",
                            if (current.signalLost) "—" else current.smoothedPulse?.let { "%.0f".format(it) } ?: "—",
                            36,
                            if (current.signalLost || current.smoothedPulse == null) PmColors.Dim else PmColors.Text,
                            Modifier.weight(if (current.monitor) 1.3f else 1f)
                        )
                        if (!current.monitor) Metric(
                            "Offset",
                            (if (current.offset > 0) "−" else "") + "%.1f".format(current.offset),
                            36, PmColors.Text, Modifier.weight(1f)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val total = current.totalDurationMs.coerceAtLeast(1L).toFloat()
                    val progress = (current.runningElapsedMs / total).coerceIn(0f, 1f)
                    val sync = ((syncWindowMin(total / 60_000.0) * 60_000.0) / total).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(PmColors.Surface2))
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PmColors.Brass))
                        // Marks the end of the sync window (metronome mirrors pulse until here; a no-pulse session holds the fallback BPM until here).
                        Row(Modifier.fillMaxWidth()) {
                            if (!current.monitor) {
                                if (sync > 0f) Box(Modifier.fillMaxWidth(sync).height(12.dp))
                                Box(Modifier.width(1.dp).height(12.dp).background(PmColors.Muted))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatElapsed(current.elapsedTotalMs)} elapsed", color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 12.sp))
                        Text("${formatElapsed(current.remainingTotalMs)} left", color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 12.sp))
                    }
                }

                Row(
                    Modifier.fillMaxWidth().height(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (current.signalLost) {
                        StatusChip(
                            "Signal lost · reconnecting",
                            PmColors.Red.copy(alpha = 0.14f), PmColors.RedSoftText,
                            borderColor = PmColors.Red.copy(alpha = 0.4f)
                        )
                    }
                    if (isBatteryLow(battery)) {
                        Row(
                            Modifier.height(30.dp).border(1.dp, PmColors.RedText.copy(alpha = 0.35f), RoundedCornerShape(15.dp))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                Modifier.size(width = 14.dp, height = 8.dp)
                                    .border(1.2.dp, PmColors.RedText, RoundedCornerShape(2.dp)).padding(1.dp)
                            ) { Box(Modifier.size(width = 2.dp, height = 8.dp).background(PmColors.RedText)) }
                            Text("Battery low", color = PmColors.RedText, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp))
                        }
                    }
                    if (current.targetReached && !current.monitor) StatusChip("Target reached", PmColors.Brass, PmColors.Background)
                    Box(Modifier.weight(1f))
                    val headMs = SessionStateBus.audioPlaybackHeadPositionMs?.invoke()
                    if (headMs != null) {
                        Text("beat head ${formatElapsed(headMs)}", color = PmColors.Dim, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp))
                    }
                }
            }
        }

        PillButton(
            "Stop",
            onClick = stopSession,
            filled = false, height = 56.dp,
            modifier = Modifier.padding(top = 14.dp, bottom = pinnedButtonBottomGap())
        )
    }
}

@Composable
private fun Metric(label: String, value: String, sizeSp: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = color, style = TextStyle(fontFamily = PmFonts.Display, fontSize = sizeSp.sp, lineHeight = (sizeSp * 0.95).sp))
        MonoLabel(label, tracking = 0.08f)
    }
}

/** Shown when system Back is pressed mid-session; Stop behaves exactly like the Stop button. */
@Composable
private fun StopSessionDialog(onKeepGoing: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepGoing,
        containerColor = PmColors.Surface,
        title = { Text("Stop session?", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Display, fontSize = 26.sp)) },
        text = { Text("The metronome stops and the session ends.", color = PmColors.Muted, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onStop) { Text("Stop", color = PmColors.RedText, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onKeepGoing) { Text("Keep going", color = PmColors.Text) }
        }
    )
}
