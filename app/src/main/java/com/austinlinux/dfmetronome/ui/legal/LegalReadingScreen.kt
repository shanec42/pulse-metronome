package com.austinlinux.dfmetronome.ui.legal

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.austinlinux.dfmetronome.ui.theme.PmColors
import com.austinlinux.dfmetronome.ui.theme.QuietTextButton
import com.austinlinux.dfmetronome.ui.theme.ScreenTitle

/** Full-screen reader for the FAQ and Privacy policy, rendered from the bundled Markdown. */
@Composable
fun LegalReadingScreen(doc: LegalDoc, onBack: () -> Unit, onOpenDoc: (LegalDoc) -> Unit) {
    val context = LocalContext.current
    // Read off the main thread; the screen shows its background for the few ms this takes.
    val blocks by produceState<List<LegalBlock>?>(initialValue = null, doc) {
        value = withContext(Dispatchers.IO) { parseLegalMarkdown(loadLegalDoc(context, doc)) }
    }
    Column(Modifier.fillMaxSize().background(PmColors.Background)) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp)) { QuietTextButton("← Back", onBack) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 48.dp)) {
            ScreenTitle(
                when (doc) {
                    LegalDoc.PRIVACY -> "Privacy policy"
                    LegalDoc.INFO -> "How to use"
                    else -> "FAQ"
                }
            )
            blocks?.let { LegalDocument(it, LegalStyle.READING, onOpenDoc) }
        }
    }
}
