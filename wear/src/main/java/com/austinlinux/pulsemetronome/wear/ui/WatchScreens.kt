package com.austinlinux.pulsemetronome.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import com.austinlinux.pulsemetronome.wear.session.WatchUiState
import com.austinlinux.pulsemetronome.wear.ui.theme.WatchColors
import com.austinlinux.pulsemetronome.wear.ui.theme.WatchType

/**
 * One watch screen for [state]. [ambient] swaps in the near-blank always-on face while a session runs;
 * [onPrimary] is the Allow / Try again / Open settings button.
 */
@Composable
fun WatchScreen(
    state: WatchUiState,
    time: String,
    ambient: Boolean,
    systemStoppedPrompting: Boolean,
    onPrimary: () -> Unit,
) {
    val sessionRunning = state is WatchUiState.Sensing || state == WatchUiState.FindingPulse ||
        state == WatchUiState.ContactLost || state == WatchUiState.LinkLost
    if (ambient && sessionRunning) {
        AmbientScreen(time)
        return
    }
    val copy = watchCopy(state, systemStoppedPrompting)
    Box(Modifier.fillMaxSize().background(WatchColors.Background)) {
        TimeArc(time, WatchColors.Text)
        Column(
            Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 32.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                WatchUiState.NeedsPermission -> {
                    AppGlyph(22.dp, WatchColors.Red, 1.6f)
                    Spacer(Modifier.height(7.dp))
                    Body(copy.body!!, WatchColors.Text)
                    Spacer(Modifier.height(11.dp))
                    Pill(copy.button!!, WatchColors.Red, WatchColors.OnRed, onPrimary)
                }
                WatchUiState.PermissionDenied -> {
                    Headline(copy.head!!)
                    Spacer(Modifier.height(6.dp))
                    Body(copy.body!!, WatchColors.Muted)
                    Spacer(Modifier.height(11.dp))
                    Pill(copy.button!!, WatchColors.Surface2, WatchColors.Text, onPrimary)
                }
                WatchUiState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(15.dp).background(WatchColors.Green.copy(alpha = 0.15f), CircleShape), Alignment.Center) {
                            Box(Modifier.size(9.dp).background(WatchColors.Green, CircleShape))
                        }
                        StatusText(copy.status!!, WatchColors.Muted)
                    }
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        copy.body!!,
                        style = WatchType.Headline.copy(color = WatchColors.Text, textAlign = TextAlign.Center),
                    )
                }
                WatchUiState.LinkLost, WatchUiState.StoppedTrying -> {
                    val dotColor = if (state == WatchUiState.LinkLost) WatchColors.Brass else WatchColors.Dim
                    StatusRow(copy.status!!, dotColor, if (state == WatchUiState.LinkLost) WatchColors.Muted else WatchColors.Dim)
                    Spacer(Modifier.height(8.dp))
                    Headline(copy.head!!)
                    Spacer(Modifier.height(6.dp))
                    Body(copy.body!!, WatchColors.Muted)
                }
                else -> MeasurementScreen(state, copy)
            }
        }
    }
}

/** FindingPulse, Sensing and ContactLost: status, number, BPM, trace and an optional message. */
@Composable
private fun MeasurementScreen(state: WatchUiState, copy: WatchCopy) {
    val brass = state == WatchUiState.FindingPulse || state == WatchUiState.ContactLost
    StatusRow(copy.status!!, if (brass) WatchColors.Brass else WatchColors.Green, WatchColors.Muted)
    Spacer(Modifier.height(3.dp))
    val bpm = (state as? WatchUiState.Sensing)?.bpm
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            bpm?.toString() ?: "—",
            style = WatchType.Pulse.copy(color = if (bpm != null) WatchColors.Text else WatchColors.Dim),
            modifier = Modifier.alignByBaseline(),
        )
        BasicText("BPM", style = WatchType.BpmLabel.copy(color = WatchColors.Muted), modifier = Modifier.alignByBaseline())
    }
    Spacer(Modifier.height(3.dp))
    WatchEcg(
        mode = when (state) {
            is WatchUiState.Sensing -> WatchEcgMode.TRACE
            WatchUiState.FindingPulse -> WatchEcgMode.SCAN
            else -> WatchEcgMode.FLAT
        },
        bpm = bpm ?: 60,
        animate = true,
    )
    copy.body?.let {
        Spacer(Modifier.height(5.dp))
        // 13 sp, as on the board: two lines still clear the round bottom edge.
        BasicText(it, style = WatchType.Body.copy(fontSize = 13.sp, lineHeight = 17.5.sp, color = WatchColors.Muted, textAlign = TextAlign.Center))
    }
}

/** Black always-on face: a hairline glyph and SENSING, nothing animated. */
@Composable
private fun AmbientScreen(time: String) {
    Box(Modifier.fillMaxSize().background(WatchColors.AmbientBg)) {
        TimeArc(time, WatchColors.AmbientInk)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppGlyph(20.dp, WatchColors.AmbientInk, 1.1f)
            Spacer(Modifier.height(7.dp))
            BasicText(
                "SENSING",
                style = WatchType.Status.copy(color = WatchColors.AmbientInk, letterSpacing = 0.12.em),
            )
        }
    }
}

@Composable
private fun TimeArc(time: String, color: Color) {
    CurvedLayout {
        basicCurvedText(time, style = CurvedTextStyle(fontSize = WatchType.Time.fontSize, fontWeight = WatchType.Time.fontWeight, color = color))
    }
}

@Composable
private fun StatusRow(text: String, dot: Color, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).background(dot, CircleShape))
        StatusText(text, ink)
    }
}

@Composable
private fun StatusText(text: String, color: Color) = BasicText(text.uppercase(), style = WatchType.Status.copy(color = color))

@Composable
private fun Headline(text: String) =
    BasicText(text, style = WatchType.Headline.copy(color = WatchColors.Text, textAlign = TextAlign.Center))

@Composable
private fun Body(text: String, color: Color) =
    BasicText(text, style = WatchType.Body.copy(color = color, textAlign = TextAlign.Center))

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(48.dp).widthIn(min = 110.dp).clip(RoundedCornerShape(24.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = WatchType.Button.copy(color = fg)) }
}

/** The launcher glyph (trapezoid with a pulse line) in one colour; [stroke] is in 24-unit glyph space. */
@Composable
private fun AppGlyph(size: androidx.compose.ui.unit.Dp, color: Color, stroke: Float) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        scale(u, u, pivot = Offset.Zero) {
            val body = Path().apply { moveTo(9f, 2.5f); lineTo(15f, 2.5f); lineTo(20f, 21.5f); lineTo(4f, 21.5f); close() }
            val beat = Path().apply {
                moveTo(6.4f, 14f); lineTo(9.4f, 14f); lineTo(10.6f, 11.6f); lineTo(12f, 17f)
                lineTo(13.3f, 9f); lineTo(14.4f, 14f); lineTo(17.6f, 14f)
            }
            drawPath(body, color, style = Stroke(stroke, join = StrokeJoin.Round))
            drawPath(beat, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
