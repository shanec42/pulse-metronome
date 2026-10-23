package com.austinlinux.dfmetronome.ui.legal

import org.junit.Assert.assertEquals
import org.junit.Test

class LegalMarkdownTest {
    @Test fun `headings at three levels`() {
        assertEquals(
            listOf(LegalBlock.Heading(1, "A"), LegalBlock.Heading(2, "B"), LegalBlock.Heading(3, "C")),
            parseLegalMarkdown("# A\n\n## B\n\n### C")
        )
    }

    @Test fun `consecutive lines join into one paragraph and blank lines separate`() {
        assertEquals(
            listOf(
                LegalBlock.Paragraph(listOf(LegalSpan.Text("one two", false))),
                LegalBlock.Paragraph(listOf(LegalSpan.Text("three", false)))
            ),
            parseLegalMarkdown("one\ntwo\n\nthree")
        )
    }

    @Test fun `bold and link spans inside a paragraph`() {
        assertEquals(
            listOf(LegalBlock.Paragraph(listOf(
                LegalSpan.Text("a ", false), LegalSpan.Text("b", true), LegalSpan.Text(" c ", false),
                LegalSpan.Link("d", "faq.md")
            ))),
            parseLegalMarkdown("a **b** c [d](faq.md)")
        )
    }

    @Test fun `bullet list with bold inside items`() {
        assertEquals(
            listOf(LegalBlock.Bullets(listOf(
                listOf(LegalSpan.Text("x", true), LegalSpan.Text(" y", false)),
                listOf(LegalSpan.Text("z", false))
            ))),
            parseLegalMarkdown("- **x** y\n- z")
        )
    }

    @Test fun `paragraph directly before a list stays separate`() {
        val blocks = parseLegalMarkdown("intro\n- item")
        assertEquals(LegalBlock.Paragraph(listOf(LegalSpan.Text("intro", false))), blocks[0])
        assertEquals(LegalBlock.Bullets(listOf(listOf(LegalSpan.Text("item", false)))), blocks[1])
    }

    @Test fun `unknown syntax is kept as plain text`() {
        assertEquals(listOf(LegalBlock.Paragraph(listOf(LegalSpan.Text("> quote | table", false)))), parseLegalMarkdown("> quote | table"))
    }

    @Test fun `link classification`() {
        assertEquals(LinkAction.InApp(LegalDoc.FAQ), classifyLink("faq.md"))
        assertEquals(LinkAction.InApp(LegalDoc.PRIVACY), classifyLink("./privacy.md"))
        assertEquals(LinkAction.InApp(LegalDoc.FAQ), classifyLink("legal/faq.md"))
        assertEquals(LinkAction.External("https://github.com/x"), classifyLink("https://github.com/x"))
        assertEquals(LinkAction.None, classifyLink("mailto:a@b.c"))
        assertEquals(LinkAction.None, classifyLink("other.md"))
        // The notice is never a link target from inside the FAQ/Privacy (it would stack a second gate).
        assertEquals(LinkAction.None, classifyLink("first-launch.md"))
    }
}
