package com.austinlinux.pulsemetronome.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.pulsemetronome.source.fitbit.FitbitStatus
import com.austinlinux.pulsemetronome.ui.theme.OutlinePill
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts

private const val SET_UP_HR_SERVICE = "Set up HR Service"
private const val CHECK_AGAIN_PILL = "Check again"

/** The exact card text for [status]. */
fun fitbitCardCopy(status: FitbitStatus): WatchCardCopy = when (status) {
    FitbitStatus.Off, FitbitStatus.Waiting ->
        WatchCardCopy("Waiting for Fitbit", null, "Open HR Service on your watch.", SET_UP_HR_SERVICE)
    is FitbitStatus.Receiving -> WatchCardCopy("Fitbit connected", "HR SERVICE · ${status.bpm} BPM", null, null)
    FitbitStatus.NoReading ->
        WatchCardCopy("Fitbit connected", "HR SERVICE · NO READING", "Check the watch is snug on your wrist.", null)
    FitbitStatus.PortBusy -> WatchCardCopy(
        "Fitbit connection in use", null,
        "Another app is receiving from HR Service. Close it (for example FB Heart Rate Monitor), then tap Check again.",
        CHECK_AGAIN_PILL
    )
}

/** The Fitbit half of the Pulse-from card: status dot, title, sub, message and a pill ([onHelp] for setup, [onCheckAgain] to retry). */
@Composable
fun FitbitStatusRow(status: FitbitStatus, onHelp: () -> Unit, onCheckAgain: () -> Unit) {
    val copy = fitbitCardCopy(status)
    val dot = when (status) {
        is FitbitStatus.Receiving -> PmColors.Green
        FitbitStatus.Off, FitbitStatus.Waiting, FitbitStatus.NoReading -> PmColors.Brass
        FitbitStatus.PortBusy -> PmColors.Dim
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(17.dp).clip(CircleShape).background(dot.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(9.dp).clip(CircleShape).background(dot)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    copy.title, color = PmColors.Text,
                    style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                )
                copy.sub?.let {
                    Text(
                        it, color = PmColors.Muted, style = TextStyle(fontFamily = PmFonts.Mono, fontSize = 11.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            copy.pill?.let {
                OutlinePill(it, onClick = if (status == FitbitStatus.PortBusy) onCheckAgain else onHelp, enabled = true)
            }
        }
        copy.message?.let {
            Text(
                it, color = PmColors.Muted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 37.dp, end = 16.dp, bottom = 14.dp)
            )
        }
    }
}
