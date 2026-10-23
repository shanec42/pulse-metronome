package com.austinlinux.dfmetronome.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.austinlinux.dfmetronome.ui.theme.PmColors
import com.austinlinux.dfmetronome.ui.theme.PmFonts

/** NOTICE is the first-launch gate layout; READING is the FAQ / Privacy layout. */
enum class LegalStyle { NOTICE, READING }

private val HairlineColor = PmColors.Text.copy(alpha = 0.08f)

private fun body(size: Int, line: Int, color: Color = PmColors.Body) =
    TextStyle(fontFamily = PmFonts.Body, fontSize = size.sp, lineHeight = line.sp, color = color)

private fun display(size: Int, line: Int, color: Color) =
    TextStyle(fontFamily = PmFonts.Display, fontSize = size.sp, lineHeight = line.sp, color = color)

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
}

/** Renders parsed legal Markdown blocks in the NOTICE or READING style. */
@Composable
fun LegalDocument(
    blocks: List<LegalBlock>,
    style: LegalStyle,
    onOpenDoc: (LegalDoc) -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val onLink: (String) -> Unit = { target ->
        when (val a = classifyLink(target)) {
            is LinkAction.InApp -> onOpenDoc(a.doc)
            is LinkAction.External ->
                // openUri throws when no app can handle the link (e.g. no browser); leave the link inert.
                try { uriHandler.openUri(a.url) } catch (_: IllegalArgumentException) { }
            LinkAction.None -> Unit
        }
    }
    val notice = style == LegalStyle.NOTICE
    val linkStyle = SpanStyle(color = PmColors.Brass, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)

    fun spansToText(spans: List<LegalSpan>): AnnotatedString = buildAnnotatedString {
        for (s in spans) when (s) {
            is LegalSpan.Text ->
                if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = PmColors.Text)) { append(s.text) }
                else append(s.text)
            is LegalSpan.Link -> {
                // Styles go in the 2nd argument, otherwise the link renders unstyled.
                val link = LinkAnnotation.Clickable(s.target, TextLinkStyles(linkStyle)) { onLink(s.target) }
                withLink(link) {
                    append(s.text)
                    if (classifyLink(s.target) is LinkAction.External) append(" ↗")
                }
            }
        }
    }

    Column(modifier) {
        var first = true
        for (block in blocks) {
            val isFirst = first
            first = false
            when (block) {
                is LegalBlock.Heading -> when {
                    notice && block.level == 1 -> Text(block.text, style = display(44, 45, PmColors.Text))
                    block.level == 1 -> Text(block.text, Modifier.padding(top = 10.dp), style = display(20, 25, PmColors.Muted))
                    block.level == 2 -> Column(Modifier.padding(top = 32.dp)) {
                        Hairline()
                        Text(block.text, Modifier.padding(top = 22.dp), style = display(24, 29, PmColors.Text))
                    }
                    else -> Text(block.text, Modifier.padding(top = 20.dp), style = display(20, 25, PmColors.Text))
                }
                is LegalBlock.Paragraph -> {
                    val single = block.spans.singleOrNull() as? LegalSpan.Link
                    if (single != null) {
                        if (notice) Column(Modifier.padding(top = 10.dp)) {
                            Hairline()
                            Box(Modifier.padding(top = 14.dp).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                                Text(spansToText(block.spans), style = body(15, 24))
                            }
                        } else Box(Modifier.padding(top = 6.dp).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                            Text(spansToText(block.spans), style = body(15, 24))
                        }
                    } else if (notice) {
                        Text(spansToText(block.spans), Modifier.padding(top = if (isFirst) 0.dp else 18.dp), style = body(18, 27))
                    } else {
                        Text(spansToText(block.spans), Modifier.padding(top = 12.dp), style = body(15, 24))
                    }
                }
                is LegalBlock.Bullets -> Column(Modifier.padding(top = if (notice) 22.dp else 12.dp)) {
                    block.items.forEachIndexed { i, item ->
                        if (notice) {
                            Hairline()
                            Row(Modifier.padding(vertical = 14.dp)) {
                                Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(PmColors.Brass))
                                Text(spansToText(item), Modifier.padding(start = 12.dp), style = body(15, 23))
                            }
                        } else {
                            Row(Modifier.padding(top = if (i == 0) 0.dp else 10.dp)) {
                                Box(Modifier.padding(top = 10.dp).size(5.dp).clip(CircleShape).background(PmColors.Brass))
                                Text(spansToText(item), Modifier.padding(start = 13.dp), style = body(15, 24))
                            }
                        }
                    }
                }
            }
        }
    }
}
