package com.austinlinux.pulsemetronome.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Palette shared with the phone app (Theme.kt) plus the ambient-only inks from the handoff board. */
object WatchColors {
    val Background = Color(0xFF17120F)
    val Surface2 = Color(0xFF2A221D)
    val Text = Color(0xFFEFE7DA)
    val Muted = Color(0xFFA89C8D)
    val Dim = Color(0xFF7D7266)
    val Red = Color(0xFFD4343A)
    val OnRed = Color(0xFFFFF6EE)
    val Brass = Color(0xFFCFAE74)
    val Green = Color(0xFF5FBF7A)
    val AmbientBg = Color(0xFF000000)
    val AmbientInk = Color(0xFF6E655C)
    val GhostRed = Color(0xFFB8262C)
    val TrailRed = Color(0xFFC8232B)
    val HeadRed = Color(0xFFE23A40)
}

/** Type scale from the handoff spec; system serif/sans/mono stand in for the board's Instrument Serif, Geist and Geist Mono. */
object WatchType {
    val Pulse = TextStyle(fontFamily = FontFamily.Serif, fontSize = 64.sp, lineHeight = 58.sp, letterSpacing = (-0.02).em)
    val Headline = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 24.sp)
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 18.9.sp)
    val Status = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)
    val BpmLabel = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val Button = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val Time = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
