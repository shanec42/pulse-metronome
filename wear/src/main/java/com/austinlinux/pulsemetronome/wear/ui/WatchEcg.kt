package com.austinlinux.pulsemetronome.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.austinlinux.pulsemetronome.wear.ui.theme.WatchColors

/** What the watch trace is doing. */
enum class WatchEcgMode { SCAN, FLAT, TRACE }

// The board's 300 x 80 path, drawn at half size (150 x 40 dp).
private const val BOARD_W = 300f
private const val BOARD_H = 80f

private fun boardPath(): Path = Path().apply {
    moveTo(0f, 52f); lineTo(58f, 52f)
    cubicTo(64f, 52f, 67f, 44f, 72f, 44f); cubicTo(77f, 44f, 80f, 52f, 86f, 52f)
    lineTo(104f, 52f); lineTo(109f, 58f); lineTo(118f, 6f); lineTo(127f, 76f); lineTo(133f, 52f); lineTo(154f, 52f)
    cubicTo(164f, 52f, 170f, 36f, 180f, 36f); cubicTo(190f, 36f, 194f, 52f, 202f, 52f)
    lineTo(300f, 52f)
}

/**
 * The ECG under the number: a ghost of the full path, plus (for [WatchEcgMode.TRACE]) two trails and a bright head
 * that sweep once per beat at [bpm], or (for SCAN) a dot scanning the faint trace. [animate] is false in ambient.
 */
@Composable
fun WatchEcg(mode: WatchEcgMode, bpm: Int, animate: Boolean, modifier: Modifier = Modifier) {
    val path = remember { boardPath() }
    val measure = remember { PathMeasure().also { it.setPath(path, false) } }
    val total = remember { measure.length }
    val periodMs = if (mode == WatchEcgMode.TRACE) (60_000 / bpm.coerceIn(30, 240)) else 1_600
    val progress = if (animate && mode != WatchEcgMode.FLAT) {
        // Keyed on the period so a new BPM restarts the sweep.
        rememberInfiniteTransition(label = "ecg").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep$periodMs",
        ).value
    } else 0f

    Canvas(modifier.width(150.dp).height(40.dp)) {
        val unit = size.width / BOARD_W // board units -> px
        val round = { dp: Float -> Stroke(dp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) }
        val ghostAlpha = if (mode == WatchEcgMode.TRACE) 0.12f else 0.18f
        drawScaled(path, unit, WatchColors.GhostRed.copy(alpha = ghostAlpha), round(2.5f))
        if (mode == WatchEcgMode.TRACE && animate) {
            val end = progress * total
            drawSegment(measure, total, end, 0.45f, unit, WatchColors.TrailRed.copy(alpha = 0.35f), round(2.5f))
            drawSegment(measure, total, end, 0.18f, unit, WatchColors.TrailRed, round(3f))
            val head = measure.getPosition(end)
            drawLine(
                WatchColors.HeadRed, Offset(head.x * unit, head.y * unit), Offset(head.x * unit + 0.1f, head.y * unit),
                strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
            )
        } else if (mode == WatchEcgMode.SCAN && animate) {
            drawCircle(WatchColors.HeadRed.copy(alpha = 0.7f), 3.dp.toPx(), Offset(progress * BOARD_W * unit, 52f * unit))
        } else if (mode == WatchEcgMode.SCAN) {
            drawCircle(WatchColors.HeadRed.copy(alpha = 0.7f), 3.dp.toPx(), Offset(0f, 52f * unit))
        }
    }
}

/** Draws [path] (board units) scaled by [unit] with a px-width [style]. */
private fun DrawScope.drawScaled(path: Path, unit: Float, color: androidx.compose.ui.graphics.Color, style: Stroke) {
    scale(unit, unit, pivot = Offset.Zero) { drawPath(path, color, style = Stroke(style.width / unit, cap = style.cap, join = style.join)) }
}

/** Draws the [fraction] of the path that ends at [end] (path length units). */
private fun DrawScope.drawSegment(
    measure: PathMeasure, total: Float, end: Float, fraction: Float, unit: Float,
    color: androidx.compose.ui.graphics.Color, style: Stroke,
) {
    val seg = Path()
    measure.getSegment((end - fraction * total).coerceAtLeast(0f), end, seg, true)
    drawScaled(seg, unit, color, style)
}
