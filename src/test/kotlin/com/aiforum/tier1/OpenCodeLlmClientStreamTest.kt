package com.aiforum.tier1

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.OpenCodeLlmClient
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-1: the streaming [OpenCodeLlmClient] plumbing — that it spawns `opencode run --format json -m <model>`,
 * reads cumulative NDJSON text parts into [AguiEvent]s via [com.aiforum.llm.OpenCodeStreamParser], and
 * classifies the finished run. The subprocess is a `/bin/sh` script printing canned opencode NDJSON (the same
 * substitution seam as ProcessLlmClientTest); the pure mapping is proven in OpenCodeStreamParserTest.
 */
@Tag("tier1")
class OpenCodeLlmClientStreamTest {

    private class StreamShellClient(private val script: String) :
        OpenCodeLlmClient(command = "opencode", defaultModel = "lmstudio/qwen/qwen3.5-9b", agent = "", workingDir = "", pollMillis = 5) {
        var argv: List<String> = emptyList()
        override fun spawn(argv: List<String>): Process {
            this.argv = argv
            return ProcessBuilder("/bin/sh", "-c", script).start()
        }
    }

    private fun request(timeout: Duration, runId: String = "n") = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol"),
        timeout = timeout,
        runId = runId,
    )

    private fun record(): Pair<MutableList<AguiEvent>, AguiEventSink> {
        val events = mutableListOf<AguiEvent>()
        return events to AguiEventSink { events.add(it) }
    }

    private fun text(id: String, cumulative: String) =
        """{"type":"text","part":{"id":"$id","type":"text","text":"$cumulative"}}"""
    private fun printScript(lines: List<String>) = "printf '%s\\n' " + lines.joinToString(" ") { "'$it'" }

    @Test
    fun `cumulative text parts stream as suffix deltas and the result is the full reply`() {
        val lines = listOf(
            """{"type":"step_start","part":{"id":"prt_a","type":"step-start"}}""",
            text("prt_b", "Indexes "),
            text("prt_b", "Indexes help"),
            """{"type":"step_finish","part":{"id":"prt_c","type":"step-finish","reason":"stop"}}""",
        )
        val client = StreamShellClient(printScript(lines))
        val (events, sink) = record()

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)

        assertEquals("Indexes help", resp.text)
        assertEquals(
            listOf(
                AguiEvent.RunStarted("n"),
                AguiEvent.TextDelta("n", "Indexes "),
                AguiEvent.TextDelta("n", "help"),
                AguiEvent.RunFinished("n"),
            ),
            events,
        )
        assertTrue(client.argv.containsAll(listOf("run", "--format", "json", "-m", "lmstudio/qwen/qwen3.5-9b")))
    }

    @Test
    fun `a failed run emits RunStarted then RunError and rethrows the taxonomy exception`() {
        val client = StreamShellClient("exit 1") // no events, non-zero exit
        val (events, sink) = record()

        assertThrows(LlmException.ProcessError::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)
        }
        assertEquals(AguiEvent.RunStarted("n"), events.first())
        assertTrue(events.last() is AguiEvent.RunError)
    }
}
