package com.aiforum.tier0

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-0: the [LlmClient] streaming-overload DEFAULT. A non-streaming backend (and the test double) must
 * satisfy the streaming path by degrading the whole reply to a single TextDelta, framed by RunStarted /
 * RunFinished, and surface failures as RunError. Pure logic — a fake [LlmClient] implements only the
 * blocking [LlmClient.generate]; the default 3-arg overload is what's under test.
 */
@Tag("tier0")
class StreamingSeamDefaultTest {

    private fun req(id: String = "node-1") =
        LlmRequest(PromptContext("sys", emptyList()), PersonaRef("p", "P"), Duration.ofSeconds(1), runId = id)

    private fun recorder(): Pair<MutableList<AguiEvent>, AguiEventSink> {
        val events = mutableListOf<AguiEvent>()
        return events to AguiEventSink { events.add(it) }
    }

    @Test
    fun `a non-streaming reply degrades to RunStarted, one TextDelta, RunFinished`() {
        val llm = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("Indexes help")
        }
        val (events, sink) = recorder()
        val resp = llm.generate(req(), CancellationToken(), sink)
        assertEquals("Indexes help", resp.text)
        assertEquals(
            listOf(
                AguiEvent.RunStarted("node-1"),
                AguiEvent.TextDelta("node-1", "Indexes help"),
                AguiEvent.RunFinished("node-1"),
            ),
            events,
        )
    }

    @Test
    fun `a failure emits RunStarted then RunError(message) and rethrows`() {
        val llm = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse =
                throw LlmException.Timeout()
        }
        val (events, sink) = recorder()
        assertThrows(LlmException.Timeout::class.java) { llm.generate(req(), CancellationToken(), sink) }
        assertEquals(
            listOf(
                AguiEvent.RunStarted("node-1"),
                AguiEvent.RunError("node-1", "generation timed out"),
            ),
            events,
        )
    }

    @Test
    fun `empty output emits no TextDelta`() {
        val llm = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("")
        }
        val (events, sink) = recorder()
        llm.generate(req(), CancellationToken(), sink)
        assertEquals(listOf(AguiEvent.RunStarted("node-1"), AguiEvent.RunFinished("node-1")), events)
    }
}
