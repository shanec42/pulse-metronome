package com.austinlinux.dfmetronome.ui.legal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalContentTest {
    private fun doc(d: LegalDoc) = File("../docs/legal/${d.fileName}").readText()

    @Test fun `every legal doc exists and starts with a level-1 heading`() {
        for (d in LegalDoc.entries) {
            val blocks = parseLegalMarkdown(doc(d))
            assertTrue("${d.fileName} should start with # heading", (blocks.first() as? LegalBlock.Heading)?.level == 1)
        }
    }

    @Test fun `every link in the legal docs resolves`() {
        for (d in LegalDoc.entries) {
            val links = parseLegalMarkdown(doc(d)).flatMap { b ->
                when (b) {
                    is LegalBlock.Paragraph -> b.spans
                    is LegalBlock.Bullets -> b.items.flatten()
                    is LegalBlock.Heading -> emptyList()
                }
            }.filterIsInstance<LegalSpan.Link>()
            for (l in links) assertTrue("${d.fileName}: unresolved link ${l.target}", classifyLink(l.target) != LinkAction.None)
        }
    }

    @Test fun `notice links to the FAQ and the FAQ links to the privacy policy`() {
        fun linksOf(d: LegalDoc) = parseLegalMarkdown(doc(d)).filterIsInstance<LegalBlock.Paragraph>().flatMap { it.spans }.filterIsInstance<LegalSpan.Link>().map { classifyLink(it.target) }
        assertTrue(LinkAction.InApp(LegalDoc.FAQ) in linksOf(LegalDoc.NOTICE))
        assertTrue(LinkAction.InApp(LegalDoc.PRIVACY) in linksOf(LegalDoc.FAQ))
    }

    @Test fun `info doc is titled How to use`() {
        val first = parseLegalMarkdown(doc(LegalDoc.INFO)).first() as LegalBlock.Heading
        assertTrue(first.text.contains("How to use"))
    }

    @Test fun `faq has the six new questions`() {
        val headings = parseLegalMarkdown(doc(LegalDoc.FAQ)).filterIsInstance<LegalBlock.Heading>().map { it.text }
        listOf(
            "I don't have a pulse oximeter, or my pulse oximeter doesn't have bluetooth",
            "I can't typically feel my pulse without using my fingers on my wrist",
            "Do I need to put my fingers on my wrist to make this work?",
            "Quick read instructions",
            "I didn't reach my target BPM",
            "What does the message \"With the current settings, the metronome may not reach your target BPM this session\" mean?"
        ).forEach { q -> assertTrue("missing: $q", headings.any { it == q }) }
    }
}
