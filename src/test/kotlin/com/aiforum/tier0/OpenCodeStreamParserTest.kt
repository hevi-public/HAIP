package com.aiforum.tier0

import com.aiforum.agui.AguiEvent
import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.LlmException
import com.aiforum.llm.OpenCodeStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure NDJSON → [AguiEvent] normalisation for `opencode run --format json`, plus its result
 * classification. opencode `text` parts are CUMULATIVE per `part.id`, so the parser emits only the new
 * suffix as each delta; [toResponse] mirrors the other backends' taxonomy.
 */
@Tag("tier0")
class OpenCodeStreamParserTest {

    private fun text(id: String, cumulative: String) =
        """{"type":"text","part":{"id":"$id","type":"text","text":"$cumulative"}}"""
    private val stepStart = """{"type":"step_start","part":{"id":"prt_a","type":"step-start"}}"""
    private fun stepFinish(reason: String) =
        """{"type":"step_finish","part":{"id":"prt_c","type":"step-finish","reason":"$reason","tokens":{"total":10}}}"""

    @Test
    fun `cumulative text parts become suffix deltas in order`() {
        val p = OpenCodeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(stepStart))
        assertEquals(listOf(AguiEvent.TextDelta("n", "Indexes ")), p.onLine(text("prt_b", "Indexes ")))
        assertEquals(listOf(AguiEvent.TextDelta("n", "help here")), p.onLine(text("prt_b", "Indexes help here")))
        assertEquals("Indexes help here", p.finalText())
    }

    @Test
    fun `a single text event yields one delta of the whole text`() {
        val p = OpenCodeStreamParser("n")
        assertEquals(listOf(AguiEvent.TextDelta("n", "hello world")), p.onLine(text("prt_b", "hello world")))
        assertEquals("hello world", p.finalText())
    }

    @Test
    fun `a repeated identical cumulative value emits nothing`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(text("prt_b", "same"))
        assertEquals(emptyList<AguiEvent>(), p.onLine(text("prt_b", "same")))
    }

    @Test
    fun `non-text lines and junk are ignored`() {
        val p = OpenCodeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(stepStart))
        assertEquals(emptyList<AguiEvent>(), p.onLine(stepFinish("stop")))
        assertEquals(emptyList<AguiEvent>(), p.onLine("not json"))
        assertEquals(emptyList<AguiEvent>(), p.onLine("   "))
    }

    @Test
    fun `toResponse returns the sanitised reply on a clean stop`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(text("prt_b", "Indexes help here"))
        p.onLine(stepFinish("stop"))
        val resp = p.toResponse(exitCode = 0)
        assertEquals("Indexes help here", resp.text)
    }

    @Test
    fun `toResponse strips and flags leaked reasoning`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(text("prt_b", "<think>plan</think>Real answer"))
        p.onLine(stepFinish("stop"))
        val resp = p.toResponse(exitCode = 0)
        assertEquals("Real answer", resp.text)
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak)
    }

    @Test
    fun `toResponse maps a non-zero exit to ProcessError`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(text("prt_b", "partial"))
        val ex = assertThrows(LlmException.ProcessError::class.java) { p.toResponse(exitCode = 3) }
        assertEquals(3, ex.exitCode)
    }

    @Test
    fun `toResponse maps no text to EmptyOutput`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(stepFinish("stop"))
        assertThrows(LlmException.EmptyOutput::class.java) { p.toResponse(exitCode = 0) }
    }

    @Test
    fun `toResponse maps a length-truncated finish to MalformedOutput`() {
        val p = OpenCodeStreamParser("n")
        p.onLine(text("prt_b", "half a th"))
        p.onLine(stepFinish("length"))
        assertThrows(LlmException.MalformedOutput::class.java) { p.toResponse(exitCode = 0) }
    }

    @Test
    fun `an error event makes toResponse a ProcessError`() {
        val p = OpenCodeStreamParser("n")
        p.onLine("""{"type":"error","part":{"type":"error","error":"provider unreachable"}}""")
        assertThrows(LlmException.ProcessError::class.java) { p.toResponse(exitCode = 0) }
    }
}
