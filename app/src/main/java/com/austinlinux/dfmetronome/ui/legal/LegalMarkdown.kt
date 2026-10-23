package com.austinlinux.dfmetronome.ui.legal

sealed interface LegalSpan {
    data class Text(val text: String, val bold: Boolean) : LegalSpan
    data class Link(val text: String, val target: String) : LegalSpan
}

sealed interface LegalBlock {
    data class Heading(val level: Int, val text: String) : LegalBlock
    data class Paragraph(val spans: List<LegalSpan>) : LegalBlock
    data class Bullets(val items: List<List<LegalSpan>>) : LegalBlock
}

enum class LegalDoc(val fileName: String) { NOTICE("first-launch.md"), FAQ("faq.md"), PRIVACY("privacy.md"), INFO("info.md") }

sealed interface LinkAction {
    data class InApp(val doc: LegalDoc) : LinkAction
    data class External(val url: String) : LinkAction
    data object None : LinkAction
}

private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\[(.+?)]\\((.+?)\\)")

/** Splits one line of text into plain, bold and link spans. */
fun parseInline(src: String): List<LegalSpan> {
    val out = mutableListOf<LegalSpan>()
    var last = 0
    for (m in INLINE.findAll(src)) {
        if (m.range.first > last) out += LegalSpan.Text(src.substring(last, m.range.first), false)
        val bold = m.groups[1]
        out += if (bold != null) LegalSpan.Text(bold.value, true) else LegalSpan.Link(m.groupValues[2], m.groupValues[3])
        last = m.range.last + 1
    }
    if (last < src.length) out += LegalSpan.Text(src.substring(last), false)
    return out
}

/** Parses the legal docs' Markdown subset (headings, paragraphs, bullets, bold, links) into blocks. */
fun parseLegalMarkdown(text: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    val para = mutableListOf<String>()
    var list: MutableList<List<LegalSpan>>? = null
    fun flushPara() { if (para.isNotEmpty()) { blocks += LegalBlock.Paragraph(parseInline(para.joinToString(" "))); para.clear() } }
    fun flushList() { list?.let { blocks += LegalBlock.Bullets(it) }; list = null }
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank()) { flushPara(); flushList(); continue }
        val h = HEADING.matchEntire(line)
        if (h != null) { flushPara(); flushList(); blocks += LegalBlock.Heading(h.groupValues[1].length, h.groupValues[2]); continue }
        if (line.startsWith("- ")) { flushPara(); (list ?: mutableListOf<List<LegalSpan>>().also { list = it }) += parseInline(line.substring(2)); continue }
        flushList()
        para += line.trim()
    }
    flushPara(); flushList()
    return blocks
}

/** In-app for a bundled legal doc, browser for http(s), otherwise not a link. */
fun classifyLink(target: String): LinkAction {
    if (target.startsWith("https://") || target.startsWith("http://")) return LinkAction.External(target)
    val name = target.removePrefix("./").removePrefix("legal/")
    // The notice is only ever shown by the launch gate, never opened from inside another doc.
    return LegalDoc.entries.firstOrNull { it != LegalDoc.NOTICE && it.fileName == name }?.let { LinkAction.InApp(it) } ?: LinkAction.None
}
