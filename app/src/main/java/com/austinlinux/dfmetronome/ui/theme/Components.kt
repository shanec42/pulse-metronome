package com.austinlinux.dfmetronome.ui.theme

import com.austinlinux.dfmetronome.ble.scanPillState
import com.austinlinux.dfmetronome.ble.ScanPillState
import com.austinlinux.dfmetronome.ble.CONNECT_RETRY_AFTER_MS
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import com.austinlinux.dfmetronome.ble.SCAN_WINDOW_MS
import com.austinlinux.dfmetronome.ble.scanFillFraction
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Small uppercase monospaced section / metric label. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = PmColors.Muted, tracking: Float = 0.12f) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        style = TextStyle(
            fontFamily = PmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp,
            letterSpacing = tracking.toDouble().em
        )
    )
}

/** Rounded dark-brown card that groups related rows. */
@Composable
fun PmCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PmColors.Surface)
    ) { content() }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PmColors.Hairline))
}

/** Title-bar style text button ("History", "← Back"). */
@Composable
fun QuietTextButton(label: String, onClick: () -> Unit, horizontalPadding: Dp = 12.dp) {
    Box(
        modifier = Modifier.height(44.dp).clickable(onClick = onClick).padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, color = PmColors.Text,
        style = TextStyle(fontFamily = PmFonts.Display, fontSize = 34.sp, lineHeight = 34.sp)
    )
}

/** Large pill button. Filled when [filled]; outlined otherwise. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    height: Dp = 58.dp,
    container: Color = PmColors.Red,
    content: Color = Color(0xFFFFF6EE)
) {
    val shape = RoundedCornerShape(height / 2)
    val base = modifier.fillMaxWidth().height(height).clip(shape)
    val styled = when {
        !enabled -> base.background(PmColors.Surface2)
        filled -> base.background(container)
        else -> base.border(1.dp, PmColors.Outline, shape)
    }
    Box(
        modifier = styled.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (!enabled) PmColors.Dim else if (filled) content else PmColors.Text,
            style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        )
    }
}

/** Small outlined pill (Scan / Rescan). */
@Composable
fun OutlinePill(label: String, onClick: () -> Unit, enabled: Boolean) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier.height(44.dp).clip(shape).border(1.dp, PmColors.Outline, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (enabled) PmColors.Text else PmColors.Dim,
            style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        )
    }
}

/** Pill chip used for Signal lost / Target reached / history status badges. */
@Composable
fun StatusChip(label: String, background: Color, textColor: Color, borderColor: Color? = null, height: Dp = 30.dp) {
    val shape = RoundedCornerShape(height / 2)
    var m = Modifier.height(height).clip(shape).background(background)
    if (borderColor != null) m = m.border(1.dp, borderColor, shape)
    Box(m.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = textColor, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
    }
}

/**
 * − [number] + control for integer fields. The number is directly editable (digits only);
 * [onBump] receives the signed [step], and keeps firing while a button is held down.
 */
@Composable
fun NumberStepper(value: Int?, isError: Boolean, onValueChange: (Int?) -> Unit, onBump: (Int) -> Unit, step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton("−", "Decrease") { onBump(-step) }
        BasicTextField(
            value = value?.toString() ?: "",
            onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.toIntOrNull()) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(PmColors.Text),
            textStyle = TextStyle(
                fontFamily = PmFonts.Display, fontSize = 30.sp, textAlign = TextAlign.Center,
                color = if (isError) PmColors.RedText else PmColors.Text
            ),
            modifier = Modifier.width(56.dp)
        )
        StepperButton("+", "Increase") { onBump(step) }
    }
}

private const val HOLD_INITIAL_DELAY_MS = 400L
private const val HOLD_START_INTERVAL_MS = 150L
private const val HOLD_MIN_INTERVAL_MS = 40L

/** Fires [onStep] once on press, then repeats (accelerating) until the finger lifts. */
@Composable
private fun StepperButton(symbol: String, description: String, onStep: () -> Unit) {
    val latest by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(PmColors.Surface2)
            .semantics { contentDescription = description; role = Role.Button; onClick { latest(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    latest()
                    val repeat = scope.launch {
                        delay(HOLD_INITIAL_DELAY_MS)
                        var interval = HOLD_START_INTERVAL_MS
                        while (true) {
                            latest()
                            delay(interval)
                            interval = maxOf(HOLD_MIN_INTERVAL_MS, interval - 10)
                        }
                    }
                    try { tryAwaitRelease() } finally { repeat.cancel() }
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(symbol, color = PmColors.Text, fontSize = 20.sp) }
}

/** Two-line label (title + optional mono/hint subtitle) used on the left of settings rows. */
@Composable
fun RowTitle(title: String, subtitle: String? = null, subtitleColor: Color = PmColors.Muted, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        if (subtitle != null) {
            Text(subtitle, color = subtitleColor, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp), maxLines = 1)
        }
    }
}

/** Waiting-screen pill: Scan now -> 4 s brass fill while scanning -> pulsing "Connecting…" while a
 *  connect is pending -> "Retry" once that has run for CONNECT_RETRY_AFTER_MS. Only idle/Retry are tappable. */
@Composable
fun ScanFillPill(scanningSince: Long?, connectingSince: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(scanningSince) {
        if (scanningSince == null) { progress.snapTo(0f); return@LaunchedEffect }
        // Resume from the window's real elapsed time - an auto-rescan may have opened it before
        // this pill was composed.
        val start = scanFillFraction(scanningSince, SystemClock.elapsedRealtime())
        progress.snapTo(start)
        progress.animateTo(1f, tween(((1f - start) * SCAN_WINDOW_MS).toInt(), easing = LinearEasing))
    }
    // Re-read the clock when a pending connect crosses the retry threshold, so Retry appears on time.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectingSince) {
        now = SystemClock.elapsedRealtime()
        if (connectingSince != null) {
            delay((connectingSince + CONNECT_RETRY_AFTER_MS - now).coerceAtLeast(0L))
            now = SystemClock.elapsedRealtime()
        }
    }
    val state = scanPillState(scanningSince, connectingSince, now)
    val pulse by rememberInfiniteTransition(label = "connectingPulse").animateFloat(
        initialValue = 1f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "connectingAlpha"
    )
    val label = when (state) {
        ScanPillState.IDLE -> "Scan now"
        ScanPillState.SCANNING -> "Scanning…"
        ScanPillState.CONNECTING -> "Connecting…"
        ScanPillState.RETRY -> "Retry"
    }
    val tappable = state == ScanPillState.IDLE || state == ScanPillState.RETRY
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.width(180.dp).height(48.dp).clip(shape).border(1.dp, PmColors.Outline, shape)
            .clickable(enabled = tappable, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (state == ScanPillState.CONNECTING) pulse else 1f }
                .drawWithContent {
                    val fill = when (state) {
                        ScanPillState.SCANNING -> progress.value
                        ScanPillState.CONNECTING -> 1f
                        else -> 0f
                    }
                    clipRect(right = size.width * fill) { this@drawWithContent.drawContent() }
                }
                .background(PmColors.Brass)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = PmColors.Background, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }
    }
}

// Muted (not Dim) so this legal line stays readable against the background.
@Composable
fun SmallPrint(text: String, modifier: Modifier = Modifier, centered: Boolean = false, lineBreak: LineBreak = LineBreak.Paragraph) {
    Text(
        text, modifier = modifier, color = PmColors.Muted,
        style = TextStyle(
            fontFamily = PmFonts.Body, fontSize = 12.sp, lineHeight = 17.4.sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            lineBreak = lineBreak
        )
    )
}


/** Modal confirmation card; outside tap or back = cancel, the red pill confirms. */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(28.dp), color = PmColors.Surface) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, color = PmColors.Text, modifier = Modifier.padding(horizontal = 4.dp),
                    style = TextStyle(fontFamily = PmFonts.Display, fontSize = 28.sp, lineHeight = 31.sp))
                Text(body, color = PmColors.Body, modifier = Modifier.padding(horizontal = 4.dp),
                    style = TextStyle(fontFamily = PmFonts.Body, fontSize = 14.sp, lineHeight = 21.sp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    DialogPill("Cancel", PmColors.Surface2, PmColors.Text, onCancel)
                    DialogPill(confirmLabel, PmColors.Red, Color(0xFFFFF6EE), onConfirm)
                }
            }
        }
    }
}

@Composable
private fun DialogPill(label: String, background: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(background)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = textColor, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)) }
}

/** Visual switch (46x28 track); put the toggling on the enclosing row, not here. */
@Composable
fun PmSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) PmColors.Red else PmColors.Surface2, tween(200), label = "switchTrack")
    val knobX by animateDpAsState(if (checked) 21.dp else 3.dp, tween(200), label = "switchKnob")
    Box(
        Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(track)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    ) {
        Box(Modifier.offset(x = knobX, y = 3.dp).size(22.dp).clip(CircleShape).background(PmColors.Text))
    }
}

/** Design 2026-10-16: space between a message bar and the navigation bar. */
val MESSAGE_BAR_GAP = 12.dp
/** Design 2026-10-16: background left below the last item of a scrolling list. */
val LIST_END_GAP = 28.dp

/** Design 2026-10-16: pinned bottom buttons clear the gesture handle's swipe zone by 12 dp; 3-button bars have their own targets, so 8 dp. */
fun pinnedButtonGap(threeButtonNav: Boolean): Dp = if (threeButtonNav) 8.dp else 12.dp

/** [pinnedButtonGap] for the current navigation mode: only 3-button navigation reports a tappable bottom inset. */
@Composable
fun pinnedButtonBottomGap(): Dp =
    pinnedButtonGap(threeButtonNav = WindowInsets.tappableElement.getBottom(LocalDensity.current) > 0)

/** Plain bottom snackbar without an action; the caller decides how long to show it. */
@Composable
fun MessageBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = MESSAGE_BAR_GAP)
            .clip(RoundedCornerShape(16.dp)).background(PmColors.Snackbar)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) { Text(text, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp)) }
}

/** Bar shown while an end-of-session alarm rings on a screen without its own Dismiss (Setup): "Alarm ringing" + Dismiss. */
@Composable
fun AlarmDismissBar(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PmColors.Snackbar)
            .heightIn(min = 52.dp).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Alarm ringing", color = PmColors.Text, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        Box(Modifier.height(44.dp).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Dismiss", color = PmColors.Brass, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }
    }
}

/** Bar that drops in at the top of a screen with a short notice and no action (e.g. Setup's meter-restart hint). */
@Composable
fun TopNoticeBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(PmColors.Snackbar)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) { Text(text, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)) }
}

/** Outlined trash-can glyph (no icons library in the project). */
@Composable
fun TrashIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val sw = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(4f * u, 6f * u), Offset(20f * u, 6f * u), sw.width, StrokeCap.Round)
        drawLine(color, Offset(9f * u, 6f * u), Offset(9f * u, 3.5f * u), sw.width, StrokeCap.Round)
        drawLine(color, Offset(15f * u, 6f * u), Offset(15f * u, 3.5f * u), sw.width, StrokeCap.Round)
        drawLine(color, Offset(9f * u, 3.5f * u), Offset(15f * u, 3.5f * u), sw.width, StrokeCap.Round)
        val body = Path().apply {
            moveTo(6f * u, 6f * u); lineTo(7f * u, 20f * u); lineTo(17f * u, 20f * u); lineTo(18f * u, 6f * u)
        }
        drawPath(body, color, style = sw)
        drawLine(color, Offset(10f * u, 10f * u), Offset(10f * u, 16f * u), sw.width, StrokeCap.Round)
        drawLine(color, Offset(14f * u, 10f * u), Offset(14f * u, 16f * u), sw.width, StrokeCap.Round)
    }
}


/** Snackbar with a brass overlay that sweeps left-to-right over [durationMs] (resumes from [startedAtMs]); tapping Undo calls [onUndo]. */
@Composable
fun UndoBar(text: String, onUndo: () -> Unit, startedAtMs: Long, durationMs: Long = 4_000L, modifier: Modifier = Modifier) {
    val progress = remember(startedAtMs) { Animatable(0f) }
    LaunchedEffect(startedAtMs) {
        val start = scanFillFraction(startedAtMs, SystemClock.elapsedRealtime(), durationMs)
        progress.snapTo(start)
        progress.animateTo(1f, tween(((1f - start) * durationMs).toInt(), easing = LinearEasing))
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = MESSAGE_BAR_GAP)
            .shadow(8.dp, shape).clip(shape).background(PmColors.Snackbar)
    ) {
        UndoRow(text, PmColors.Text, FontWeight.Medium, PmColors.Brass, FontWeight.SemiBold, false, onUndo)
        Box(
            Modifier.matchParentSize()
                .drawWithContent { clipRect(right = size.width * progress.value) { this@drawWithContent.drawContent() } }
                .background(PmColors.Brass)
                .clearAndSetSemantics {}
        ) {
            UndoRow(text, PmColors.Background, FontWeight.SemiBold, PmColors.Background, FontWeight.Bold, true, null)
        }
    }
}

@Composable
private fun UndoRow(text: String, textColor: Color, textWeight: FontWeight, undoColor: Color, undoWeight: FontWeight, underline: Boolean, onUndo: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text, color = textColor, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = PmFonts.Body, fontWeight = textWeight, fontSize = 14.sp))
        val tap = if (onUndo != null) Modifier.clickable(role = Role.Button, onClick = onUndo) else Modifier
        Box(Modifier.height(44.dp).then(tap).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Undo", color = undoColor, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = undoWeight, fontSize = 14.sp, textDecoration = if (underline) TextDecoration.Underline else null))
        }
    }
}
