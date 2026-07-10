package com.aiforum.tier0

import com.aiforum.markdown.MarkdownRenderer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the markdown → trusted-HTML rendering behind reply/post bodies. No IO seam — commonmark and the
 * GraalJS-hosted highlight.js both run in-process, so this is pure input → output. It also doubles as the
 * smoke test that highlight.js actually loads and runs under GraalJS on this JDK.
 *
 * The load-bearing guarantees: (1) raw HTML in a body stays inert (the XSS firewall behind `$unsafe{}`),
 * (2) fenced blocks with a known language come back syntax-highlighted, and (3) no-language / unknown /
 * malformed blocks degrade to a plain escaped block instead of throwing.
 */
@Tag("tier0")
class MarkdownRendererTest {

    @Test
    fun `plain prose renders as a paragraph`() {
        val html = MarkdownRenderer.render("just a sentence")
        assertTrue(html.contains("<p>just a sentence</p>"), html)
    }

    @Test
    fun `markdown emphasis renders to tags`() {
        val html = MarkdownRenderer.render("this is **bold** and *italic*")
        assertTrue(html.contains("<strong>bold</strong>"), html)
        assertTrue(html.contains("<em>italic</em>"), html)
    }

    @Test
    fun `raw HTML in a body is escaped, not executed (XSS firewall)`() {
        val html = MarkdownRenderer.render("hi <script>alert('x')</script>")
        assertFalse(html.contains("<script>"), "raw <script> must never reach the output:\n$html")
        assertTrue(html.contains("&lt;script&gt;"), html)
    }

    @Test
    fun `raw HTML table is escaped — tables must come via markdown, not raw HTML`() {
        val html = MarkdownRenderer.render("<table><tr><td>x</td></tr></table>")
        assertFalse(html.contains("<table>"), "raw <table> must be inert:\n$html")
        assertTrue(html.contains("&lt;table&gt;"), html)
    }

    @Test
    fun `a link with a script-scheme destination is neutralized (URL half of the firewall)`() {
        // Mixed case included: scheme matching must be case-insensitive or it's a trivial bypass.
        for (dest in listOf("javascript:alert('x')", "data:text/html;base64,PHNjcmlwdD4=", "vbscript:msgbox", "JaVaScRiPt:alert(1)")) {
            val html = MarkdownRenderer.render("[click me]($dest)")
            val scheme = dest.substringBefore(':')
            assertFalse(html.contains("$scheme:", ignoreCase = true), "hostile $scheme: href must not survive:\n$html")
            assertTrue(html.contains("href=\"\""), "destination should be emptied, not the link dropped:\n$html")
            assertTrue(html.contains("click me"), "link text must still render:\n$html")
        }
    }

    @Test
    fun `an image with a script-scheme destination is neutralized`() {
        for (dest in listOf("javascript:alert('x')", "data:text/html;base64,PHNjcmlwdD4=", "vbscript:msgbox")) {
            val html = MarkdownRenderer.render("![pic]($dest)")
            val scheme = dest.substringBefore(':')
            assertFalse(html.contains("$scheme:", ignoreCase = true), "hostile $scheme: src must not survive:\n$html")
            assertTrue(html.contains("src=\"\""), "destination should be emptied:\n$html")
        }
    }

    @Test
    fun `safe https and relative link destinations survive sanitization`() {
        val https = MarkdownRenderer.render("[docs](https://example.com/docs)")
        assertTrue(https.contains("href=\"https://example.com/docs\""), https)
        // Internal links (quote backlinks, story refs) are relative — sanitization must not eat them.
        val relative = MarkdownRenderer.render("[a thread](/threads/1)")
        assertTrue(relative.contains("href=\"/threads/1\""), relative)
    }

    @Test
    fun `GFM pipe tables render to a real table`() {
        val md = """
            | Component | Status |
            | --------- | ------ |
            | Button    | Shipped |
        """.trimIndent()
        val html = MarkdownRenderer.render(md)
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<th>Component</th>"), html)
        assertTrue(html.contains("<td>Shipped</td>"), html)
    }

    @Test
    fun `a fenced block with a known language is syntax-highlighted`() {
        val md = "```yaml\nname: saul\nrole: frontend\n```"
        val html = MarkdownRenderer.render(md)
        assertTrue(html.contains("class=\"hljs language-yaml\""), html)
        // hljs wraps tokens in spans — proof the highlighter actually ran, not just a class slapped on.
        assertTrue(html.contains("hljs-"), "expected highlight.js token spans:\n$html")
    }

    @Test
    fun `an unknown language degrades to a plain escaped block, no exception`() {
        val md = "```notalang\nsome <code> & text\n```"
        val html = MarkdownRenderer.render(md)
        assertFalse(html.contains("hljs-"), "unknown language must not be highlighted:\n$html")
        assertTrue(html.contains("&lt;code&gt;"), "code text must still be HTML-escaped:\n$html")
    }

    @Test
    fun `a fence with no language is a plain escaped block`() {
        val md = "```\nplain & <unhighlighted>\n```"
        val html = MarkdownRenderer.render(md)
        assertFalse(html.contains("hljs"), html)
        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("&lt;unhighlighted&gt;"), html)
    }

    @Test
    fun `a single newline becomes a line break, a blank line starts a new paragraph`() {
        val oneBreak = MarkdownRenderer.render("line one\nline two")
        assertTrue(oneBreak.contains("line one<br>"), "single newline should be a <br>:\n$oneBreak")
        val paragraphs = MarkdownRenderer.render("para one\n\npara two")
        assertTrue(paragraphs.contains("<p>para one</p>"), paragraphs)
        assertTrue(paragraphs.contains("<p>para two</p>"), paragraphs)
    }

    @Test
    fun `a blank body renders to empty string`() {
        assertTrue(MarkdownRenderer.render("   ").isEmpty())
    }
}
