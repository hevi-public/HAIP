package com.aiforum.tier0

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiWire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the AG-UI wire contract. These golden-JSON assertions are the ONLY tests coupled to the AG-UI
 * spec — they pin exactly what [AguiWire] emits, so this file changes only when AG-UI's wire shape does
 * (or when we adopt the dependency). The rest of the suite asserts on the internal [AguiEvent] sealed
 * type, which is insulated from spec churn. No Spring, no IO — pure serialisation.
 */
@Tag("tier0")
class AguiWireTest {

    @Test
    fun `RunStarted maps to AG-UI RUN_STARTED`() {
        val e = AguiEvent.RunStarted("node-1")
        assertEquals("RUN_STARTED", AguiWire.type(e))
        assertEquals("""{"type":"RUN_STARTED","runId":"node-1"}""", AguiWire.encode(e))
    }

    @Test
    fun `TextDelta maps to TEXT_MESSAGE_CONTENT with runId as messageId`() {
        val e = AguiEvent.TextDelta("node-1", "Indexes help")
        assertEquals("TEXT_MESSAGE_CONTENT", AguiWire.type(e))
        assertEquals("""{"type":"TEXT_MESSAGE_CONTENT","messageId":"node-1","delta":"Indexes help"}""", AguiWire.encode(e))
    }

    @Test
    fun `TextDelta delta is JSON-escaped`() {
        val e = AguiEvent.TextDelta("node-1", "line\none\t\"quoted\"")
        assertEquals(
            """{"type":"TEXT_MESSAGE_CONTENT","messageId":"node-1","delta":"line\none\t\"quoted\""}""",
            AguiWire.encode(e),
        )
    }

    @Test
    fun `ToolCallStart maps to TOOL_CALL_START`() {
        val e = AguiEvent.ToolCallStart("node-1", "tc-7", "WebFetch")
        assertEquals("TOOL_CALL_START", AguiWire.type(e))
        assertEquals("""{"type":"TOOL_CALL_START","toolCallId":"tc-7","toolCallName":"WebFetch"}""", AguiWire.encode(e))
    }

    @Test
    fun `ToolCallEnd maps to TOOL_CALL_END`() {
        val e = AguiEvent.ToolCallEnd("node-1", "tc-7")
        assertEquals("TOOL_CALL_END", AguiWire.type(e))
        assertEquals("""{"type":"TOOL_CALL_END","toolCallId":"tc-7"}""", AguiWire.encode(e))
    }

    @Test
    fun `RunFinished maps to AG-UI RUN_FINISHED`() {
        val e = AguiEvent.RunFinished("node-1")
        assertEquals("RUN_FINISHED", AguiWire.type(e))
        assertEquals("""{"type":"RUN_FINISHED","runId":"node-1"}""", AguiWire.encode(e))
    }

    @Test
    fun `RunError maps to AG-UI RUN_ERROR with message`() {
        val e = AguiEvent.RunError("node-1", "generation timed out")
        assertEquals("RUN_ERROR", AguiWire.type(e))
        assertEquals("""{"type":"RUN_ERROR","message":"generation timed out"}""", AguiWire.encode(e))
    }

    @Test
    fun `isTerminal is true only for RunFinished and RunError`() {
        assertEquals(true, AguiEvent.RunFinished("n").isTerminal)
        assertEquals(true, AguiEvent.RunError("n", "x").isTerminal)
        assertEquals(false, AguiEvent.RunStarted("n").isTerminal)
        assertEquals(false, AguiEvent.TextDelta("n", "d").isTerminal)
    }
}
