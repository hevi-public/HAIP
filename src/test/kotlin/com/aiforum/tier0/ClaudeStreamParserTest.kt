package com.aiforum.tier0

import com.aiforum.agui.AguiEvent
import com.aiforum.llm.ClaudeStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure NDJSON → [AguiEvent] normalisation for `claude -p --output-format stream-json`. Canned
 * lines in, events out — no subprocess. The terminal `result` line is captured (for [LlmResponseParser]),
 * not emitted as a delta.
 */
@Tag("tier0")
class ClaudeStreamParserTest {

    private fun delta(text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}}"""

    private val toolStart =
        """{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"WebFetch"}}}"""
    private val toolStop = """{"type":"stream_event","event":{"type":"content_block_stop","index":1}}"""
    private val systemInit = """{"type":"system","subtype":"init","session_id":"s"}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
    private fun result(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","stop_reason":"end_turn"}"""

    @Test
    fun `content_block_delta lines become TextDeltas in order`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(systemInit))
        assertEquals(listOf(AguiEvent.TextDelta("n", "Index")), p.onLine(delta("Index")))
        assertEquals(listOf(AguiEvent.TextDelta("n", "es help")), p.onLine(delta("es help")))
    }

    @Test
    fun `the result line is captured and emits nothing`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(result("Indexes help")))
        assertTrue(p.resultJson.contains("\"result\":\"Indexes help\""))
    }

    @Test
    fun `a tool_use block emits ToolCallStart then ToolCallEnd paired by index`() {
        val p = ClaudeStreamParser("n")
        assertEquals(listOf(AguiEvent.ToolCallStart("n", "toolu_1", "WebFetch")), p.onLine(toolStart))
        assertEquals(listOf(AguiEvent.ToolCallEnd("n", "toolu_1")), p.onLine(toolStop))
    }

    @Test
    fun `a whole assistant message becomes one TextDelta when no token deltas were seen`() {
        val p = ClaudeStreamParser("n")
        assertEquals(listOf(AguiEvent.TextDelta("n", "Whole answer")), p.onLine(assistant("Whole answer")))
    }

    @Test
    fun `a trailing assistant message is suppressed once token deltas have streamed`() {
        val p = ClaudeStreamParser("n")
        p.onLine(delta("partial"))
        assertEquals(emptyList<AguiEvent>(), p.onLine(assistant("partial and more")))
    }

    @Test
    fun `a non-JSON line is ignored`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine("not json at all"))
        assertEquals(emptyList<AguiEvent>(), p.onLine("   "))
    }
}
