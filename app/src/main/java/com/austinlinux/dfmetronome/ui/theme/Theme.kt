package com.austinlinux.dfmetronome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Warm "walnut metronome" palette from the Claude Design prototype (docs/GUI). */
object PmColors {
    val Background = Color(0xFF17120F)
    val Surface = Color(0xFF211A16)
    val Surface2 = Color(0xFF2A221D)
    val Text = Color(0xFFEFE7DA)
    val Muted = Color(0xFFA89C8D)
    val Body = Color(0xFFC9BDAE) // Reading text on long documents (legal screens), a step below Text.
    val Dim = Color(0xFF7D7266)
    val Red = Color(0xFFD4343A)
    val RedText = Color(0xFFF07068)
    val RedSoftText = Color(0xFFF4A49E)
    val Brass = Color(0xFFCFAE74)
    val Green = Color(0xFF5FBF7A)
    val Hairline = Color(0x12EFE7DA)
    val Outline = Color(0x2EEFE7DA)
    val Snackbar = Color(0xFF2E2622)
}

// The prototype uses Instrument Serif (display), Geist (body) and Geist Mono (labels). Those
// fonts are not bundled, so the platform serif / sans / monospace families stand in.
object PmFonts {
    val Display: FontFamily = FontFamily.Serif
    val Body: FontFamily = FontFamily.SansSerif
    val Mono: FontFamily = FontFamily.Monospace
}

private val PmColorScheme = darkColorScheme(
    primary = PmColors.Red,
    onPrimary = Color(0xFFFFF6EE),
    background = PmColors.Background,
    onBackground = PmColors.Text,
    surface = PmColors.Background,
    onSurface = PmColors.Text,
    surfaceVariant = PmColors.Surface,
    onSurfaceVariant = PmColors.Muted,
    error = PmColors.RedText,
    onError = PmColors.Background
)

private val PmTypography = Typography(
    bodyLarge = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = PmFonts.Body, fontSize = 13.sp, lineHeight = 18.sp),
    titleMedium = TextStyle(fontFamily = PmFonts.Display, fontSize = 20.sp)
)

@Composable
fun PulseMetronomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PmColorScheme, typography = PmTypography, content = content)
}
