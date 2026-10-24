package com.austinlinux.pulsemetronome.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts

/** Pulse (red) vs metronome BPM (brass) over elapsed session time; compact = label-free sparkline. */
@Composable
fun SessionGraph(model: SessionGraphModel?, compact: Boolean, modifier: Modifier = Modifier) {
    if (compact) {
        Box(modifier.fillMaxWidth().height(56.dp)) {
            when {
                model == null -> Unit
                !model.hasEnoughData -> NoData(radius = 10f, fontSize = 11)
                else -> Plot(model, compact = true, Modifier.fillMaxSize())
            }
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
            if (model?.hasPulse != false) LegendItem("Pulse", PmColors.Red)
            LegendItem("Metronome", PmColors.Brass)
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(240.dp)) {
            Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 4.dp, top = 10.dp, bottom = 24.dp)) {
                when {
                    model == null -> Unit
                    !model.hasEnoughData -> NoData(radius = 12f, fontSize = 12)
                    else -> Plot(model, compact = false, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 2.dp).background(color))
        Text(label, color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp))
    }
}

@Composable
private fun NoData(radius: Float, fontSize: Int) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRoundRect(
                color = PmColors.Text.copy(alpha = 0.10f),
                cornerRadius = CornerRadius(radius.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            )
        },
        contentAlignment = Alignment.Center
    ) {
        Text("Not enough data to graph", color = PmColors.Dim, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = fontSize.sp))
    }
}

/** Draws the pulse and metronome series for a session on a Canvas, with axes unless compact. */
@Composable
private fun Plot(model: SessionGraphModel, compact: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = PmFonts.Mono, fontSize = 10.sp, color = PmColors.Dim)
    Canvas(modifier) {
        val range = (model.yMax - model.yMin).coerceAtLeast(1).toFloat()
        fun x(ms: Long) = size.width * ms / model.xMaxMs.toFloat()
        fun y(v: Int) = size.height * (model.yMax - v) / range

        if (!compact) {
            for (t in model.yTicks) {
                val yy = y(t)
                drawLine(PmColors.Text.copy(alpha = 0.07f), Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
                val layout = measurer.measure(t.toString(), labelStyle)
                drawText(layout, topLeft = Offset(-8.dp.toPx() - layout.size.width, yy - layout.size.height / 2f))
            }
            for (tick in model.xTicks) {
                val layout = measurer.measure(tick.label, labelStyle)
                // Centred on its tick, but the end label is kept from running past the plot's edge.
                val left = (x(tick.elapsedMs) - layout.size.width / 2f).coerceAtMost(size.width + 4.dp.toPx() - layout.size.width)
                drawText(layout, topLeft = Offset(left, size.height + 20.dp.toPx() - layout.size.height))
            }
        }

        val stroke = (if (compact) 1.5f else 2f).dp.toPx()
        drawSeries(model.bpmPoints, PmColors.Brass, stroke, ::x, ::y)
        for (seg in model.pulseSegments) drawSeries(seg, PmColors.Red, stroke, ::x, ::y)

        model.lostMarkerMs?.let { ms ->
            val xx = x(ms)
            if (compact) {
                drawCircle(PmColors.Red, radius = 3.dp.toPx(), center = Offset(xx, size.height + 3.dp.toPx()))
            } else {
                drawRect(PmColors.Red, topLeft = Offset(xx - 1.dp.toPx(), size.height - 8.dp.toPx()), size = Size(2.dp.toPx(), 9.dp.toPx()))
                drawCircle(PmColors.Red, radius = 3.5.dp.toPx(), center = Offset(xx, size.height + 0.5.dp.toPx()))
            }
        }
    }
}

/** Strokes one continuous series as a polyline (a lone point becomes a dot). */
private fun DrawScope.drawSeries(points: List<GraphPoint>, color: Color, stroke: Float, x: (Long) -> Float, y: (Int) -> Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        // A lone reading between two signal-loss gaps has no line to draw; show it as a dot.
        drawCircle(color, radius = stroke, center = Offset(x(points[0].elapsedMs), y(points[0].value)))
        return
    }
    val path = Path().apply {
        moveTo(x(points[0].elapsedMs), y(points[0].value))
        for (p in points.drop(1)) lineTo(x(p.elapsedMs), y(p.value))
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
