package com.aiforum.dto

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Shared one-line text preview: flatten the body's GFM to plain text (a raw preview leaks `>`/`##`/`|`
 * syntax into every rail box, branch-index entry, and "in reply to" line), collapse whitespace, and
 * truncate with an ellipsis if over [max]. Flattening walks the same commonmark AST family as
 * MarkdownRenderer, so the preview always agrees with what the body renders as: markers vanish, text
 * and code literals survive, links/images keep only their label/alt (no room for URLs in a preview).
 */
object Snippet {

    fun oneLine(body: String, max: Int): String {
        val s = plainText(body).replace(Regex("\\s+"), " ").trim()
        return if (s.length <= max) s else s.take(max).trimEnd() + "…"
    }

    /** GFM → plain text: concatenated Text/code literals with a space at each block boundary. */
    fun plainText(body: String): String {
        val sb = StringBuilder()
        parser.parse(body).accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                sb.append(fencedCodeBlock.literal).append(' ')
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                sb.append(indentedCodeBlock.literal).append(' ')
            }

            override fun visit(paragraph: Paragraph) {
                visitChildren(paragraph)
                sb.append(' ')
            }

            override fun visit(heading: Heading) {
                visitChildren(heading)
                sb.append(' ')
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                sb.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                sb.append(' ')
            }

            // Table cells (GFM tables parse to CustomNode subtypes): keep the cell text, add the
            // separator a pipe used to provide so adjacent cells don't run together.
            override fun visit(customNode: CustomNode) {
                visitChildren(customNode)
                sb.append(' ')
            }

            // Raw HTML is sanitised away at render time (the XSS firewall) — previews drop it too.
            override fun visit(htmlInline: HtmlInline) {}

            override fun visit(htmlBlock: HtmlBlock) {}
        })
        return sb.toString()
    }

    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
}
