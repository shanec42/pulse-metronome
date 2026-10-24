package com.austinlinux.pulsemetronome.ui.legal

import com.austinlinux.pulsemetronome.ui.theme.pinnedButtonBottomGap
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.ui.theme.MessageBar
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts

/** One-time notice shown when the session history couldn't be upgraded and a fresh one was started. */
@Composable
fun UpgradeFailedScreen(onSaveBackup: () -> Unit, onContinue: () -> Unit, message: String?) {
    Box(Modifier.fillMaxSize().background(PmColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).border(1.dp, PmColors.Brass, CircleShape))
                    Text(
                        "AFTER UPDATING",
                        style = TextStyle(fontFamily = PmFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.3.sp, color = PmColors.Brass)
                    )
                }
                Text(
                    "History couldn't be upgraded",
                    modifier = Modifier.padding(top = 16.dp),
                    style = TextStyle(fontFamily = PmFonts.Display, fontSize = 40.sp, lineHeight = 42.sp, color = PmColors.Text)
                )
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    BodyText("This version of Pulse Metronome couldn't read your existing session history, so it has started a new, empty history. Everything else works as normal, and new sessions are saved as usual.")
                    BodyText("Your earlier history has been kept as a backup on this phone and should be safe. If something on the phone itself has failed, the backup may be affected too, and that's outside what the app can fix.")
                    Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(PmColors.Hairline))
                    Text(
                        "Please report the problem on GitHub so it can be looked into: (GitHub link — available when the repository is public)",
                        style = TextStyle(fontFamily = PmFonts.Body, fontSize = 14.sp, lineHeight = 22.sp, color = PmColors.Muted)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pinnedButtonBottomGap()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PillAction("Save backup file", onSaveBackup, Modifier.weight(1.15f), filled = false)
                PillAction("Continue", onContinue, Modifier.weight(1f), filled = true)
            }
        }
        // Lifted so it sits above the pinned buttons instead of covering them.
        if (message != null) MessageBar(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp))
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = TextStyle(fontFamily = PmFonts.Body, fontSize = 16.sp, lineHeight = 25.sp, color = PmColors.Body))
}

// Local like NoticeScreen's button: the design wants a 25% outline and 15 sp labels, which PillButton doesn't offer.
@Composable
private fun PillAction(label: String, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    val base = modifier.height(56.dp).clip(shape)
    Box(
        (if (filled) base.background(PmColors.Text) else base.border(1.dp, PmColors.Text.copy(alpha = 0.25f), shape))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            maxLines = 1,
            style = TextStyle(
                fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                color = if (filled) PmColors.Background else PmColors.Text
            )
        )
    }
}
