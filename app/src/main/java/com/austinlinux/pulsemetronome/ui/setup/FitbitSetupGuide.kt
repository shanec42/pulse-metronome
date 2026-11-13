package com.austinlinux.pulsemetronome.ui.setup

import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import com.austinlinux.pulsemetronome.source.fitbit.FitbitStatus
import com.austinlinux.pulsemetronome.ui.theme.OutlinePill
import com.austinlinux.pulsemetronome.ui.theme.PillButton
import com.austinlinux.pulsemetronome.ui.theme.PmColors
import com.austinlinux.pulsemetronome.ui.theme.PmFonts

/** Fitbit watch generations, each with its own HR Service Gallery listing. */
enum class FitbitWatchFamily(val label: String, val galleryUrl: String) {
    OS5("Sense or Versa 3", "https://gallery.fitbit.com/details/6503a799-37f7-43bf-8775-23f1742e2a4e"),
    OS4(
        "Versa 2, Versa Lite, Versa or Ionic",
        "https://gallery.fitbit.com/details/799d08f9-77d4-4a73-81dc-b187159a7124?key=c05ef0f4-c8f7-4459-94f7-50a209108fc6"
    )
}

const val FITBIT_PACKAGE = "com.fitbit.FitbitMobile"
const val FITBIT_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=$FITBIT_PACKAGE"

/** True when [isInstalled] reports the Fitbit app's package. */
fun isFitbitAppInstalled(isInstalled: (String) -> Boolean): Boolean = isInstalled(FITBIT_PACKAGE)

/** [isFitbitAppInstalled] backed by this PackageManager (needs the manifest `<queries>` entry on Android 11+). */
fun PackageManager.isFitbitAppInstalled(): Boolean = isFitbitAppInstalled { pkg ->
    try { getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
}

/** Step-by-step HR Service install dialog; step 3 turns to "Connected" once [status] is Receiving. */
@Composable
fun FitbitSetupGuide(
    status: FitbitStatus,
    fitbitAppInstalled: Boolean,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val connected = status as? FitbitStatus.Receiving
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = PmColors.Surface) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Set up HR Service", color = PmColors.Text, modifier = Modifier.padding(horizontal = 4.dp),
                    style = TextStyle(fontFamily = PmFonts.Display, fontSize = 28.sp, lineHeight = 31.sp))
                if (!fitbitAppInstalled) {
                    GuideStep("Install the Fitbit app") {
                        PillButton("Install the Fitbit app", { onOpenUrl(FITBIT_PLAY_STORE_URL) }, height = 48.dp)
                    }
                }
                GuideStep("Which Fitbit do you have?") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FitbitWatchFamily.entries.forEach { family ->
                            FamilyButton(family.label) { onOpenUrl(family.galleryUrl) }
                        }
                    }
                }
                GuideStep("Get HR Service", "Pick your watch above to open HR Service in the Fitbit Gallery, then install it.")
                if (connected != null) {
                    GuideStep("Connected — ${connected.bpm} BPM")
                } else {
                    GuideStep("Open HR Service on your watch", "Keep it open. This turns to Connected once readings arrive.")
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (connected != null) PillButton("Done", onDismiss, height = 48.dp)
                    else OutlinePill("Close", onClick = onDismiss, enabled = true)
                }
            }
        }
    }
}

/** Outlined button in PillButton's style that grows to fit a wrapped label (PillButton's height is fixed). */
@Composable
private fun FamilyButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .border(1.dp, PmColors.Outline, shape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = PmColors.Text, textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = PmFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        )
    }
}

@Composable
private fun GuideStep(title: String, body: String? = null, content: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = PmColors.Text, style = TextStyle(fontFamily = PmFonts.Body, fontSize = 15.sp))
        body?.let { Text(it, color = PmColors.Body, style = TextStyle(fontFamily = PmFonts.Body, fontSize = 13.sp, lineHeight = 19.sp)) }
        content?.invoke()
    }
}
