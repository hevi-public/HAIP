package com.aiforum.tier0

import com.aiforum.dto.Snippet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the shared one-line preview. Bodies are GFM, so the preview must FLATTEN markdown — every
 * rail box, branch-index entry, "in reply to" line, and quote snippet reads through this. The old
 * raw-text behaviour leaked `>`/`##`/`|` syntax into every sidebar ("— > Retro: what actually…").
 */
@Tag("tier0")
class SnippetTest {

    @Test
    fun `plain prose passes through and truncates with an ellipsis`() {
        assertEquals("one two three", Snippet.oneLine("one two three", 40))
        assertEquals("one two…", Snippet.oneLine("one two three", 8))
    }

    @Test
    fun `blockquote markers are stripped`() {
        assertEquals(
            "quoted line and the reply below it",
            Snippet.oneLine("> quoted line\n\nand the reply below it", 80),
        )
    }

    @Test
    fun `heading markers and emphasis are stripped`() {
        assertEquals(
            "Short version It works, but bold and code survive as text",
            Snippet.oneLine("## Short version\n\nIt *works*, but **bold** and `code` survive as text", 80),
        )
    }

    @Test
    fun `table pipes flatten to cell text`() {
        val md = "| Item | Days |\n|------|------|\n| review | ~2 |"
        val flat = Snippet.oneLine(md, 80)
        assertEquals(false, flat.contains("|"), "pipes should not leak into the preview: $flat")
        assertEquals(true, flat.contains("review"), "cell text should survive: $flat")
    }

    @Test
    fun `links keep their label and drop the url`() {
        assertEquals(
            "see YAGNI for why",
            Snippet.oneLine("see [YAGNI](https://martinfowler.com/bliki/Yagni.html) for why", 80),
        )
    }

    @Test
    fun `list markers are stripped`() {
        assertEquals(
            "first second",
            Snippet.oneLine("- first\n- second", 80),
        )
    }

    @Test
    fun `whitespace collapses across paragraphs and code fences`() {
        assertEquals(
            "before fun x() = 1 after",
            Snippet.oneLine("before\n\n```kotlin\nfun x() = 1\n```\n\nafter", 80),
        )
    }
}
