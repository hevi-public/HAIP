package com.aiforum.tier0

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.ReplySanitizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure post-processing that strips leaked chain-of-thought and flags it (never discards).
 * No Spring, no IO — every branch is driven by canned completion text. Fixtures mirror the real leaks
 * observed from Gemma via LM Studio (tagged <think> blocks and bare "thinking" preamble).
 */
@Tag("tier0")
class ReplySanitizerTest {

    // --- stripReasoning: tagged blocks come out, the reply stays -------------------------------------

    @Test
    fun `a think block is stripped and the reply survives`() {
        val r = ReplySanitizer.stripReasoning("<think>plan my reply</think>Indexes help here.")
        assertEquals("Indexes help here.", r.text)
        assertTrue(r.removedReasoning)
    }

    @Test
    fun `thinking tags are stripped case-insensitively`() {
        val r = ReplySanitizer.stripReasoning("<THINKING>\nstep 1\n</THINKING>\nReal answer")
        assertEquals("Real answer", r.text)
        assertTrue(r.removedReasoning)
    }

    @Test
    fun `a dangling open think tag with no close is stripped to the end`() {
        // A truncated reasoning dump that never closed: everything from the tag on is reasoning.
        val r = ReplySanitizer.stripReasoning("Here goes.<think>now I ramble forever")
        assertEquals("Here goes.", r.text)
        assertTrue(r.removedReasoning)
    }

    @Test
    fun `a reply that is only a think block strips to blank`() {
        val r = ReplySanitizer.stripReasoning("<think>all of it was reasoning</think>")
        assertEquals("", r.text)
        assertTrue(r.removedReasoning)
    }

    @Test
    fun `a clean reply is left untouched`() {
        val r = ReplySanitizer.stripReasoning("Just a normal reply.")
        assertEquals("Just a normal reply.", r.text)
        assertFalse(r.removedReasoning)
    }

    // --- looksLikeReasoning: the heuristic, anchored at the start -----------------------------------

    @Test
    fun `the screenshot preambles are recognised as reasoning`() {
        assertTrue(ReplySanitizer.looksLikeReasoning("The user wants me to act as Paul, a quality-minded test engineer."))
        assertTrue(ReplySanitizer.looksLikeReasoning("Thinking Process: 1. Analyze the Request..."))
        assertTrue(ReplySanitizer.looksLikeReasoning("* **Analyze the context:** The thread is starting."))
        assertTrue(ReplySanitizer.looksLikeReasoning("1. **Analyze the Request:** I need to reply."))
    }

    @Test
    fun `a reply that merely mentions the trigger words mid-sentence is not flagged`() {
        // The anchor is the whole point: "the user wants" only trips when it OPENS the reply.
        assertFalse(ReplySanitizer.looksLikeReasoning("Sounds right — the user wants a refund, so issue one."))
        assertFalse(ReplySanitizer.looksLikeReasoning("Good question. I'd batch the writes to avoid N+1."))
    }

    // --- sanitize: strip + classify, ACTUAL wins over the heuristic ---------------------------------

    @Test
    fun `tagged leak classifies as ACTUAL`() {
        val s = ReplySanitizer.sanitize("<think>scheming</think>The real reply.")
        assertEquals("The real reply.", s.text)
        assertEquals(ReasoningLeak.ACTUAL, s.leak)
    }

    @Test
    fun `untagged preamble classifies as POSSIBLE and the body is kept verbatim`() {
        val raw = "Thinking Process: I should answer the persistence question. The data contract is..."
        val s = ReplySanitizer.sanitize(raw)
        assertEquals(raw, s.text, "an untagged leak is persisted as-is — we only flag, never edit")
        assertEquals(ReasoningLeak.POSSIBLE, s.leak)
    }

    @Test
    fun `a clean reply has no leak flag`() {
        val s = ReplySanitizer.sanitize("Indexes help here.")
        assertEquals("Indexes help here.", s.text)
        assertNull(s.leak)
    }

    @Test
    fun `stripping a tag wins over a reasoning-looking remainder`() {
        // Tag removal is certain, so even if what's left still reads like reasoning it's ACTUAL.
        val s = ReplySanitizer.sanitize("<think>x</think>Analyze the context: actually this is the reply.")
        assertEquals(ReasoningLeak.ACTUAL, s.leak)
    }
}
