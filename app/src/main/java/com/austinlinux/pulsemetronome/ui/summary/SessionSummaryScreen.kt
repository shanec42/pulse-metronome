package com.austinlinux.pulsemetronome.ui.summary

import com.austinlinux.pulsemetronome.ui.theme.pinnedButtonBottomGap
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.alarm.ACTION_DISMISS_ALARM
import com.austinlinux.pulsemetronome.alarm.AlarmType
import com.austinlinux.pulsemetronome.ui.graph.SessionGraph
import com.austinlinux.pulsemetronome.ui.history.SessionSummaryDisplay
import com.austinlinux.pulsemetronome.ui.theme.ConfirmDialog
import com.austinlinux.pulsemetronome.ui.theme.MonoLabel
import com.austinlinux.pulsemetronome.ui.theme.PillButton
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.TrashIcon
import com.austinlinux.pulsemetronome.ui.theme.PmFonts
import com.austinlinux.pulsemetronome.ui.theme.QuietTextButton
import com.austinlinux.pulsemetronome.ui.theme.SmallPrint
import com.austinlinux.pulsemetronome.ui.theme.StatusChip
import com.austinlinux.pulsemetronome.ui.theme.fadeInAfterMorph
import com.austinlinux.pulsemetronome.ui.theme.sessionCardKey
import com.austinlinux.pulsemetronome.ui.theme.sessionGraphKey
import com.austinlinux.pulsemetronome.ui.theme.sessionSharedBounds

/** One finished session: stats + full graph; alarm strip and Done when arriving from a live session. */
@Composable
fun SessionSummaryScreen(viewModel: SessionSummaryViewModel, sessionId: Long, fromSession: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val alarm by viewModel.alarm.collectAsState()
    val context = LocalContext.current
    val ringing = fromSession && alarm != null
    // Same broadcast the alarm notification's Dismiss action sends - stops sound/vibration.
    val dismissAlarm = {
        context.sendBroadcast(Intent(ACTION_DISMISS_ALARM).setPackage(context.packageName))
        viewModel.clearAlarm()
    }
    val isPending by viewModel.isPending.collectAsState()
    var left by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Leaving while the alarm rings must silence it first, or it outlives the screen with no in-app control.
    // The left guard stops the Missing auto-leave from popping a second time during the pop fade.
    val leave = {
        if (!left) {
            left = true
            if (ringing) dismissAlarm()
            viewModel.deleteIfUnsaved(fromSession)
            if (fromSession) onDone() else onBack()
        }
    }
    BackHandler(onBack = leave)
    LaunchedEffect(ui) { if (ui is SummaryUi.Missing) leave() }

    val lateFade = if (fromSession) Modifier else Modifier.fadeInAfterMorph()
    Column(
        Modifier.fillMaxSize().then(if (fromSession) Modifier else Modifier.sessionSharedBounds(sessionCardKey(sessionId)))
            .background(PmColors.Background).padding(start = 20.dp, end = 20.dp, bottom = if (fromSession) pinnedButtonBottomGap() else 12.dp)
    ) {
        if (!fromSession) Box(Modifier.offset(x = (-12).dp).then(lateFade)) { QuietTextButton("← Back", leave) }
        if (ringing) AlarmStrip(alarm!!, dismissAlarm)
        val ready = ui as? SummaryUi.Ready
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val headTop = when { ringing -> 22.dp; fromSession -> 30.dp; else -> 6.dp }
            Column(Modifier.padding(top = headTop).then(lateFade), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (fromSession) {
                    Text("Session complete", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Display, fontSize = 44.sp, lineHeight = 44.sp))
                    if (ready != null) Text(ready.shortDate, color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 13.sp))
                } else if (ready != null) {
                    Text(ready.display.dateLabel, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Display, fontSize = 34.sp, lineHeight = 36.sp))
                }
            }
            if (ready != null) {
                SummaryBody(ready.display, lateFade)
                if (fromSession && isPending) SmallPrint("Not saved — History is off.", modifier = Modifier.padding(top = 10.dp))
                SessionGraph(ready.graph, compact = false, modifier = Modifier.padding(top = 22.dp).then(if (fromSession) Modifier else Modifier.sessionSharedBounds(sessionGraphKey(sessionId), fade = false)))
                SmallPrint("Readings as reported by your oximeter. Not for medical use.", lineBreak = LineBreak.Heading, modifier = Modifier.padding(top = 12.dp, start = 28.dp).then(lateFade))
                ready.display.pulseLostWarning?.let {
                    Text(it, color = PmColors.RedText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).then(lateFade))
                }
                if (!fromSession) {
                    Box(Modifier.fillMaxWidth().padding(top = 28.dp).then(lateFade), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).clickable { confirmDelete = true }.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TrashIcon(PmColors.RedText, Modifier.size(18.dp))
                            Text("Delete session", color = PmColors.RedText, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
                        }
                    }
                }
            }
        }
        if (confirmDelete) {
            ConfirmDialog("Delete this session?", "This can't be undone.", "Delete",
                onConfirm = { confirmDelete = false; viewModel.deleteThisSession() }, onCancel = { confirmDelete = false })
        }
        if (fromSession) {
            PillButton("Done", leave, modifier = Modifier.padding(top = 12.dp), height = 56.dp, container = PmColors.Text, content = PmColors.Background)
        }
    }
}

/** Banner shown while the end-of-session alarm rings, with a control to dismiss it. */
@Composable
private fun AlarmStrip(type: AlarmType, onDismiss: () -> Unit) {
    val label = when (type) {
        AlarmType.SOUND -> "Alarm sounding"
        AlarmType.VIBRATE_ONLY -> "Vibrating"
        AlarmType.SOUND_AND_VIBRATE -> "Alarm + vibration"
        AlarmType.SILENT -> ""
    }
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape).background(PmColors.Surface2)
            .border(1.dp, PmColors.Brass.copy(alpha = 0.25f), shape).padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(PmColors.Brass.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(PmColors.Brass))
        }
        MonoLabel(label, color = PmColors.Brass, modifier = Modifier.weight(1f))
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(PmColors.Text).clickable(onClick = onDismiss).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Dismiss alarm", color = PmColors.Background, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
        }
    }
}

/** The Summary's status chip and stats row for a finished session. */
@Composable
private fun SummaryBody(d: SessionSummaryDisplay, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (d.isIncomplete || d.targetReached) {
            Row(Modifier.padding(top = 14.dp)) {
                if (d.isIncomplete) StatusChip("Incomplete", Color.Transparent, PmColors.RedText, PmColors.Red.copy(alpha = 0.5f), height = 26.dp)
                else StatusChip("Target reached", PmColors.Brass, PmColors.Background, height = 26.dp)
            }
        }
        Column(
            Modifier.padding(top = 20.dp).fillMaxWidth()
                .drawBehind {
                    drawLine(PmColors.Hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(PmColors.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (d.focusLabel != null) {
                // Focus makes five stats: wrap to two rows of three equal columns.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("Duration", d.durationLabel, Modifier.weight(1f))
                    SummaryStat("Target", d.targetLabel, Modifier.weight(1f))
                    SummaryStat("Focus", d.focusLabel, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("BPM", d.bpmRangeLabel, Modifier.weight(1f))
                    if (d.pulseRangeLabel != null) SummaryStat("Pulse", d.pulseRangeLabel, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("Duration", d.durationLabel, Modifier.weight(1f))
                    SummaryStat("Target", d.targetLabel, Modifier.weight(1f))
                    SummaryStat("BPM", d.bpmRangeLabel, Modifier.weight(1f))
                    if (d.pulseRangeLabel != null) SummaryStat("Pulse", d.pulseRangeLabel, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MonoLabel(label, tracking = 0.08f)
        Text(value, color = PmColors.Text, maxLines = 1, style = TextStyle(fontFamily = PmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp))
    }
}
