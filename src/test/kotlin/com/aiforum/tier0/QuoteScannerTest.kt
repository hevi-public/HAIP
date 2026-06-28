package com.aiforum.tier0

import com.aiforum.markdown.QuoteScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure scanner that folds manual / persona markdown blockquotes into the quote graph
 * (plan_docs/comment-quotes.md §5). The matching taxonomy — resolve a blockquote to the ORIGINAL (where
 * the text is prose), not to other quoters (where it's a `> ` block); skip ambiguous / external / short
 * passages; de-dupe against real toolbar edges — is the whole correctness story, so it's unit-tested.
 */
@Tag("tier0")
class QuoteScannerTest {

    private val passage = "Now can we get back to the schema discussion"

    @Test
    fun `blockquotePassages extracts each contiguous block, stripped of markers`() {
        val md = "intro line\n> quoted one\n> still one\n\nmiddle\n> quoted two"
        assertEquals(listOf("quoted one\nstill one", "quoted two"), QuoteScanner.blockquotePassages(md))
    }

    @Test
    fun `prose drops blockquote lines`() {
        assertEquals("intro\nafter", QuoteScanner.prose("intro\n> a quote\n>also\nafter"))
    }

    @Test
    fun `derive links a blockquote to the comment whose PROSE holds the text, not other quoters`() {
        val original = "sol" to "Yep. $passage before this drifts."
        val quoterA = "owner" to "> $passage" // another quoter — has it only in a blockquote
        val typed = "saul" to "> $passage\n\nQuoted, happy?"

        val derived = QuoteScanner.derive(listOf(original, quoterA, typed), storedKeys = emptySet())

        // Both quoters resolve to sol (the prose holder) — never to each other.
        assertEquals(setOf("owner" to "sol", "saul" to "sol"), derived.map { it.srcId to it.targetId }.toSet())
    }

    @Test
    fun `derive skips a passage that no prose contains`() {
        val a = "sol" to "a totally different point"
        val b = "saul" to "> $passage" // nobody wrote this as prose
        assertEquals(emptyList<Any>(), QuoteScanner.derive(listOf(a, b), emptySet()))
    }

    @Test
    fun `derive skips an ambiguous passage that two comments hold as prose`() {
        val a = "sol" to "$passage indeed"
        val b = "mira" to "well, $passage too"
        val q = "saul" to "> $passage"
        assertEquals(emptyList<Any>(), QuoteScanner.derive(listOf(a, b, q), emptySet()))
    }

    @Test
    fun `derive does not link a comment to itself`() {
        val solo = "sol" to "$passage\n\n> $passage" // sol states it then quotes itself
        assertEquals(emptyList<Any>(), QuoteScanner.derive(listOf(solo), emptySet()))
    }

    @Test
    fun `derive de-dupes against a stored toolbar edge`() {
        val original = "sol" to "Yep. $passage before this drifts."
        val toolbar = "owner" to "> $passage" // body carries the toolbar-inserted blockquote
        // The stored edge for that toolbar quote already exists, so the blockquote must NOT re-derive it.
        val storedKeys = setOf(Triple("owner", "sol", QuoteScanner.normalize(passage)))
        assertEquals(emptyList<Any>(), QuoteScanner.derive(listOf(original, toolbar), storedKeys))
    }

    @Test
    fun `derive ignores a too-short blockquote`() {
        val a = "sol" to "ok then"
        val b = "saul" to "> ok"
        assertEquals(emptyList<Any>(), QuoteScanner.derive(listOf(a, b), emptySet()))
    }

    @Test
    fun `derive is whitespace-tolerant between the blockquote and the prose`() {
        val original = "sol" to "Now can we get back\nto the schema discussion now?" // wrapped differently
        val typed = "saul" to "> Now can we get back to the schema discussion"
        val derived = QuoteScanner.derive(listOf(original, typed), emptySet())
        assertEquals(listOf("saul" to "sol"), derived.map { it.srcId to it.targetId })
    }
}
