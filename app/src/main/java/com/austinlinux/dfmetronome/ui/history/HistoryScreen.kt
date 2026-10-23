package com.austinlinux.dfmetronome.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.austinlinux.dfmetronome.data.PulseSampleEntity
import com.austinlinux.dfmetronome.ui.graph.SessionGraph
import com.austinlinux.dfmetronome.ui.graph.SessionGraphModel
import com.austinlinux.dfmetronome.ui.graph.plannedDurationMsIfTimedOut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import com.austinlinux.dfmetronome.ui.theme.TrashIcon
import com.austinlinux.dfmetronome.ui.theme.UndoBar
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.dfmetronome.ui.theme.MonoLabel
import com.austinlinux.dfmetronome.ui.theme.PmColors
import com.austinlinux.dfmetronome.ui.theme.PmFonts
import com.austinlinux.dfmetronome.ui.theme.QuietTextButton
import com.austinlinux.dfmetronome.ui.theme.ScreenTitle
import com.austinlinux.dfmetronome.ui.theme.StatusChip
import com.austinlinux.dfmetronome.ui.theme.fadeOutEarly
import com.austinlinux.dfmetronome.ui.theme.sessionCardKey
import com.austinlinux.dfmetronome.ui.theme.sessionGraphKey
import com.austinlinux.dfmetronome.ui.theme.sessionSharedBounds

/**
 * Compose list screen: one card per past session, newest first (SessionDao.observeAll()
 * orders by startTimestamp DESC, per Task 10), showing date, duration, target BPM, the
 * starting→ending BPM range, a compact session graph, and target-reached/incomplete badges
 * via toDisplay(...). Tapping anywhere on a card opens that session's Summary.
 *
 * Compose layout itself is not unit-tested in this stack - see Task 18's note; pure logic
 * (toDisplay) is tested in HistoryDisplayMapperTest instead. Manual on-device verification
 * only for this file.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit = {}, onOpenSession: (Long) -> Unit = {}) {
    val sessions by viewModel.sessions.collectAsState()
    val historyEnabled by viewModel.historyEnabled.collectAsState()
    val pendingSwipe by viewModel.pendingSwipe.collectAsState()
    val listState = rememberLazyListState()
    var restoredId by remember { mutableStateOf<Long?>(null) }
    // LazyColumn anchors on the first visible key, so an item re-inserted above it by Undo would land off-screen.
    LaunchedEffect(restoredId, sessions) {
        val id = restoredId ?: return@LaunchedEffect
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return@LaunchedEffect
        if (idx < listState.firstVisibleItemIndex) listState.scrollToItem(idx)
        restoredId = null
    }

    Box(Modifier.fillMaxSize().background(PmColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp)) { QuietTextButton("← Back", onBack) }
            Column(Modifier.padding(start = 22.dp, top = 2.dp, end = 22.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenTitle("History")
                if (!historyEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).border(1.dp, PmColors.Muted, CircleShape))
                        Text("History is off. New sessions aren't being saved.", color = PmColors.Muted,
                            style = TextStyle(fontFamily = PmFonts.Body, fontSize = 12.sp, lineHeight = 17.sp))
                    }
                }
            }

            if (sessions.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                ) {
                    Text("No sessions yet", color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Display, fontSize = 26.sp))
                    Text("Finished sessions appear here.", color = PmColors.Muted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    // Room for the 4 s Undo bar (52 dp + its 12 dp gap) so it never hides the last card; well over Design's 28 dp minimum.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.id }) { item ->
                        val samples by remember(item.id) { viewModel.samplesFor(item.id) }.collectAsState(initial = null)
                        SwipeableCard(item, samples, swipeEnabled = canSwipe(pendingSwipe?.id), onSwiped = viewModel::onSwiped, onClick = { onOpenSession(item.id) })
                    }
                }
            }
        }
        pendingSwipe?.let { p ->
            UndoBar("Session deleted", onUndo = { restoredId = p.id; viewModel.undo() }, startedAtMs = p.startedAtMs, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** A History card wrapped in swipe-to-delete; [onSwiped] returning false (another delete pending) keeps the card in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableCard(item: HistoryItem, samples: List<PulseSampleEntity>?, swipeEnabled: Boolean, onSwiped: (Long) -> Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    // Not rememberSwipeToDismissBoxState: that one is saveable, so Undo would re-show the card stuck off-screen.
    val state = remember(item.id) {
        SwipeToDismissBoxState(
            SwipeToDismissBoxValue.Settled, density,
            confirmValueChange = { v -> v != SwipeToDismissBoxValue.Settled && currentOnSwiped(item.id) },
            positionalThreshold = { it * 0.35f }
        )
    }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(18.dp)
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.onSizeChanged { widthPx = it.width.toFloat() },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        gesturesEnabled = swipeEnabled,
        backgroundContent = {
            val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
            val fraction = if (widthPx > 0f) (abs(offset) / (0.35f * widthPx)).coerceIn(0f, 1f) else 0f
            val committed = state.targetValue != SwipeToDismissBoxValue.Settled
            val past = committed || fraction >= 1f
            val fg = if (past) Color(0xFFFFF6EE) else PmColors.RedText
            val fgScale by animateFloatAsState(if (past) 1.08f else 1f, tween(120), label = "swipeFgScale")
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (committed) PmColors.Red else PmColors.Red.copy(alpha = 0.12f + 0.3f * fraction))
                    .padding(horizontal = 22.dp),
                contentAlignment = if (toEnd) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Row(
                    Modifier.graphicsLayer { scaleX = fgScale; scaleY = fgScale },
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    TrashIcon(fg, Modifier.size(20.dp))
                    Text("Delete", color = fg, style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                }
            }
        }
    ) {
        val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
        val dragging = offset != 0f
        val pastThreshold = widthPx > 0f && abs(offset) >= 0.35f * widthPx
        val tilt = if (pastThreshold) (if (offset > 0) 1.2f else -1.2f) else 0f
        SessionSummaryCard(
            item, samples, onClick = onClick,
            modifier = Modifier.graphicsLayer {
                rotationZ = tilt
                shadowElevation = if (dragging) 12.dp.toPx() else 0f
                this.shape = shape
                clip = false
            }
        )
    }
}

/** One History card: session details plus a compact graph; tapping anywhere on it opens the Summary. */
@Composable
internal fun SessionSummaryCard(item: HistoryItem, samples: List<PulseSampleEntity>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val session = remember(item, samples) { if (samples == null) item.display else toDisplay(item.entity, samples, ::historyDateLabel) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "cardScale")
    val bg by animateColorAsState(if (pressed) PmColors.Surface2 else PmColors.Surface, tween(120), label = "cardBg")
    val graph = remember(samples) { samples?.let { SessionGraphModel.from(it, item.startTimestamp, item.pulseLostAtElapsedMs, item.entity.endTimestamp, item.entity.plannedDurationMsIfTimedOut()) } }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .sessionSharedBounds(sessionCardKey(item.id))
            .clip(shape).background(bg).border(1.dp, PmColors.Text.copy(alpha = 0.06f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(Modifier.fillMaxWidth().fadeOutEarly(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.dateLabel, color = PmColors.Text, modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = PmFonts.Display, fontSize = 20.sp)
                )
                if (session.isIncomplete) {
                    StatusChip("Incomplete", androidx.compose.ui.graphics.Color.Transparent, PmColors.RedText, PmColors.Red.copy(alpha = 0.5f), height = 24.dp)
                } else if (session.targetReached) {
                    StatusChip("Target reached", PmColors.Brass, PmColors.Background, height = 24.dp)
                }
            }
            if (session.focusLabel != null) {
                // Focus makes five stats: wrap to two rows of three equal columns.
                Row(Modifier.fillMaxWidth().fadeOutEarly(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Duration", session.durationLabel, Modifier.weight(1f))
                    Stat("Target", session.targetLabel, Modifier.weight(1f))
                    Stat("Focus", session.focusLabel, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().fadeOutEarly(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("BPM", session.bpmRangeLabel, Modifier.weight(1f))
                    if (session.pulseRangeLabel != null) Stat("Pulse", session.pulseRangeLabel, Modifier.weight(1f))
                    else Box(Modifier.weight(1f))
                    Box(Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth().fadeOutEarly(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Duration", session.durationLabel, Modifier.weight(1f))
                    Stat("Target", session.targetLabel, Modifier.weight(1f))
                    Stat("BPM", session.bpmRangeLabel, Modifier.weight(1f))
                    if (session.pulseRangeLabel != null) Stat("Pulse", session.pulseRangeLabel, Modifier.weight(1f))
                    else Box(Modifier.weight(1f))
                }
            }
            SessionGraph(graph, compact = true, modifier = Modifier.sessionSharedBounds(sessionGraphKey(item.id), fade = false))
            session.pulseLostWarning?.let { Text(it, color = PmColors.RedText, fontSize = 12.sp, modifier = Modifier.offset(y = (-2).dp).fadeOutEarly()) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MonoLabel(label, tracking = 0.08f)
        Text(value, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp))
    }
}
