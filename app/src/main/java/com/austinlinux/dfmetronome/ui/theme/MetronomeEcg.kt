package com.austinlinux.dfmetronome.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.austinlinux.dfmetronome.R

/** How the ECG line over the metronome window behaves. */
enum class EcgMode {
    /** Faint flat baseline, nothing moving. */
    FLAT,
    /** A dot sweeping the baseline - waiting on / verifying a pulse. */
    SCAN,
    /** Full ECG trace, one complete sweep per beat at the given BPM. */
    TRACE
}

// Coordinates are in the 477x823 space of the metronome artwork (see docs/GUI prototype).
private const val ART_W = 477f
private const val ART_H = 823f
private val TraceRed = Color(0xFFC8232B)
private val GhostRed = Color(0xFFB8262C)
private val HeadRed = Color(0xFFE23A40)

private fun ecgPath(): Path = Path().apply {
    moveTo(127f, 431f); lineTo(160f, 431f)
    cubicTo(168f, 431f, 172f, 418f, 178f, 418f); cubicTo(184f, 418f, 188f, 431f, 195f, 431f)
    lineTo(210f, 431f); lineTo(215.5f, 442f); lineTo(227.5f, 320f); lineTo(236.5f, 473f)
    lineTo(244.5f, 431f); lineTo(265f, 431f)
    cubicTo(276f, 431f, 283f, 407f, 293.5f, 407f); cubicTo(304f, 407f, 309f, 431f, 318f, 431f)
    lineTo(352f, 431f)
}

/**
 * The walnut metronome artwork with an animated ECG line drawn over its window. The R-wave
 * spike lands on each beat of [bpm]. Purely visual: it is driven by the BPM value, not by the
 * audio engine's actual click timing.
 */
@Composable
fun MetronomeEcg(mode: EcgMode, bpm: Double, height: Dp, modifier: Modifier = Modifier, trailLength: Float = 0.55f) {
    val path = remember { ecgPath() }
    val measure = remember { PathMeasure().also { it.setPath(path, false) } }
    val total = remember { measure.length }
    // Position (0..1 along the path) of the tallest point - the click lands there.
    val spike = remember {
        var minY = Float.MAX_VALUE; var at = 0f
        for (i in 0..400) {
            val p = measure.getPosition(total * i / 400f)
            if (p.y < minY) { minY = p.y; at = i / 400f }
        }
        at
    }
    val currentMode by rememberUpdatedState(mode)
    val currentBpm by rememberUpdatedState(bpm)
    var phase by remember { mutableFloatStateOf(0f) }
    var frameTick by remember { mutableStateOf(0L) }  // forces a redraw each frame

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                    phase += if (currentMode == EcgMode.TRACE) dt * (currentBpm.toFloat() / 60f) else dt * 0.5f
                }
                last = now
                frameTick = now
            }
        }
    }

    // Fixed height: the glow layer below uses fillMaxSize and would otherwise take the whole screen.
    Box(modifier = modifier.height(height), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Soft brass glow behind the metronome.
                drawCircle(
                    brush = Brush.radialGradient(listOf(PmColors.Brass.copy(alpha = 0.15f), Color.Transparent)),
                    radius = size.height * 0.85f
                )
            }
        )
        Box(Modifier.height(height).aspectRatio(ART_W / ART_H)) {
            Image(
                painter = painterResource(R.drawable.metronome),
                contentDescription = "Metronome",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            Canvas(Modifier.fillMaxSize()) {
                frameTick.let { }  // read so this block redraws every frame
                scale(size.width / ART_W, size.height / ART_H, pivot = Offset.Zero) {
                    val stroke = { w: Float -> Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
                    when (currentMode) {
                        EcgMode.TRACE -> {
                            drawPath(path, GhostRed.copy(alpha = 0.10f), style = stroke(3.5f))
                            val s = ((phase % 1f) + spike) % 1f
                            val lens = floatArrayOf(trailLength, trailLength * 0.66f, trailLength * 0.4f, trailLength * 0.16f)
                            val alphas = floatArrayOf(0.3f, 0.35f, 0.5f, 1f)
                            for (i in 0 until 4) {
                                val start = (s - lens[i]).coerceAtLeast(0f)
                                val seg = Path()
                                measure.getSegment(start * total, s * total, seg, true)
                                drawPath(seg, TraceRed.copy(alpha = alphas[i]), style = stroke(if (i == 3) 4.5f else 4f))
                            }
                            drawCircle(HeadRed, 5f, measure.getPosition(s * total))
                        }
                        EcgMode.SCAN -> {
                            drawLine(GhostRed.copy(alpha = 0.25f), Offset(127f, 431f), Offset(352f, 431f), 3f, StrokeCap.Round)
                            val s = phase % 1f
                            val a = 0.4f + 0.4f * kotlin.math.sin(s * Math.PI.toFloat())
                            drawCircle(HeadRed.copy(alpha = a), 5f, Offset(127f + 225f * s, 431f))
                        }
                        EcgMode.FLAT ->
                            drawLine(GhostRed.copy(alpha = 0.25f), Offset(127f, 431f), Offset(352f, 431f), 3f, StrokeCap.Round)
                    }
                }
            }
        }
    }
}
