package com.aiforum.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text

/**
 * Turns bare Shortcut story references (`sc-123`) in a parsed markdown document into links to the story
 * in Shortcut. Operates on the commonmark AST *after* parsing and *before* rendering, so:
 *  - escaping stays correct — we only add [Link] nodes the [org.commonmark.renderer.html.HtmlRenderer]
 *    renders itself; prose text is still HTML-escaped exactly as before (no raw-HTML injection);
 *  - inline code and fenced code never match — their text isn't [Text] nodes, so `sc-1` in a code
 *    sample is left untouched; and
 *  - we never nest a link inside a link.
 *
 * Pure given a base url (no IO), so it's exercised at Tier 0. The base is a `…/story/` prefix; the
 * matched id is concatenated onto it (e.g. `https://app.shortcut.com/acme/story/123`).
 */
object StoryRefLinker {

    // `sc-` (case-insensitive) + digits, not glued to a preceding word char or hyphen (so "disc-7" and
    // "abc-12" don't match) and ending on a word boundary.
    private val REF = Regex("""(?<![\w-])sc-(\d+)\b""", RegexOption.IGNORE_CASE)

    /** Just the referenced ids in a raw string, in order (used by callers that want the refs only). */
    fun ids(text: String): List<Long> = REF.findAll(text).map { it.groupValues[1].toLong() }.toList()

    /** Rewrite every eligible `sc-N` text occurrence in [root] into a link of `base` + id. */
    fun apply(root: Node, base: String) {
        root.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                val parent = text.parent
                // Skip text that's already a link's label or inline code (Code holds its own literal, so
                // this mostly guards link labels and any future text-bearing inline).
                if (parent is Link || parent is Code) return

                val literal = text.literal
                val matches = REF.findAll(literal).toList()
                if (matches.isEmpty()) return

                var cursor = 0
                for (match in matches) {
                    if (match.range.first > cursor) {
                        text.insertBefore(Text(literal.substring(cursor, match.range.first)))
                    }
                    val link = Link(base + match.groupValues[1], null)
                    link.appendChild(Text(match.value))
                    text.insertBefore(link)
                    cursor = match.range.last + 1
                }
                if (cursor < literal.length) {
                    text.insertBefore(Text(literal.substring(cursor)))
                }
                text.unlink()
            }
        })
    }
}
