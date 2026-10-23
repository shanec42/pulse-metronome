package com.austinlinux.dfmetronome.ui.legal

import com.austinlinux.dfmetronome.ui.theme.pinnedButtonBottomGap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.dfmetronome.ui.theme.PmColors
import com.austinlinux.dfmetronome.ui.theme.PmFonts

/** First-launch legal notice: scrolling text above a pinned Decline / I understand bar. */
@Composable
fun NoticeScreen(onAccept: () -> Unit, onDecline: () -> Unit, onOpenDoc: (LegalDoc) -> Unit, backEnabled: Boolean) {
    // Back must not slip past the notice; disabled while another screen is on top (e.g. during the fade to the FAQ).
    BackHandler(enabled = backEnabled) { onDecline() }
    val context = LocalContext.current
    // Read off the main thread; the screen shows its background for the few ms this takes.
    val blocks by produceState<List<LegalBlock>?>(initialValue = null, Unit) {
        value = withContext(Dispatchers.IO) { parseLegalMarkdown(loadLegalDoc(context, LegalDoc.NOTICE)) }
    }
    Column(Modifier.fillMaxSize().background(PmColors.Background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 36.dp)) {
                blocks?.let { LegalDocument(it, LegalStyle.NOTICE, onOpenDoc) }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PmColors.Background)))
            )
        }
        Row(
            Modifier.fillMaxWidth().background(PmColors.Background).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pinnedButtonBottomGap()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NoticeButton("Decline", onDecline, Modifier.weight(1f), filled = false)
            NoticeButton("I understand", onAccept, Modifier.weight(1.5f), filled = true)
        }
    }
}

// Local rather than PillButton: the design wants a 25% outline and 15 sp labels, which PillButton doesn't offer.
@Composable
private fun NoticeButton(label: String, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    val base = modifier.height(56.dp).clip(shape)
    Box(
        (if (filled) base.background(PmColors.Text) else base.border(1.dp, PmColors.Text.copy(alpha = 0.25f), shape))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (filled) PmColors.Background else PmColors.Text
            )
        )
    }
}
