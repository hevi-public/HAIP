package com.aiforum.tier0

import com.aiforum.markdown.MarkdownRenderer
import com.aiforum.markdown.StoryRefLinker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: inline `sc-N` story-reference linkification. Ref extraction is pure; the AST rewrite is proven
 * end-to-end through [MarkdownRenderer] (set the base, render, assert the anchor), which is also where the
 * escaping/code-skipping guarantees matter. Each rendering test restores the global base so it can't leak.
 */
@Tag("tier0")
class StoryRefLinkerTest {

    private val base = "https://app.shortcut.com/acme/story/"

    @Test
    fun `ids extracts every sc-N reference in order`() {
        assertEquals(listOf(42L, 7L), StoryRefLinker.ids("see sc-42 and also sc-7 please"))
    }

    @Test
    fun `ids ignores sc glued to a preceding word or hyphen`() {
        assertEquals(emptyList<Long>(), StoryRefLinker.ids("disc-7 abcsc-9 misc-3"))
    }

    @Test
    fun `a bare ref in prose becomes a link to the story`() = withBase {
        val html = MarkdownRenderer.render("let's track this in sc-42 today")
        // rel="nofollow" comes from sanitizeUrls, which stamps it on every rendered link.
        assertTrue(html.contains("""<a rel="nofollow" href="${base}42">sc-42</a>"""), html)
    }

    @Test
    fun `a ref inside inline code is not linked`() = withBase {
        val html = MarkdownRenderer.render("the literal `sc-42` stays code")
        assertTrue(html.contains("<code>sc-42</code>"), html)
        assertFalse(html.contains("""href="${base}42""""), html)
    }

    @Test
    fun `a ref inside a fenced code block is not linked`() = withBase {
        val html = MarkdownRenderer.render("```\nsc-42 in code\n```")
        assertFalse(html.contains("""href="${base}42""""), html)
    }

    @Test
    fun `an existing link label is not re-linked`() = withBase {
        val html = MarkdownRenderer.render("[sc-42](https://example.com)")
        assertTrue(html.contains("""href="https://example.com""""), html)
        assertFalse(html.contains("""href="${base}42""""), html)
    }

    @Test
    fun `with no base configured a ref is left as plain text`() {
        MarkdownRenderer.storyLinkBaseUrl = null
        val html = MarkdownRenderer.render("plain sc-99 here")
        assertFalse(html.contains("<a "), html)
        assertTrue(html.contains("sc-99"), html)
    }

    private fun withBase(block: () -> Unit) {
        val previous = MarkdownRenderer.storyLinkBaseUrl
        MarkdownRenderer.storyLinkBaseUrl = base
        try {
            block()
        } finally {
            MarkdownRenderer.storyLinkBaseUrl = previous
        }
    }
}
