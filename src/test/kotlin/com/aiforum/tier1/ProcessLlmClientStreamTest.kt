package com.aiforum.tier1

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.ProcessLlmClient
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-1: the streaming [ProcessLlmClient.generate] overload — that it spawns `--output-format stream-json`,
 * reads NDJSON line by line into [AguiEvent]s via [com.aiforum.llm.ClaudeStreamParser], and still classifies
 * the captured `result` line through [com.aiforum.llm.LlmResponseParser]. The subprocess is a `/bin/sh`
 * script printing canned NDJSON (the same substitution seam as ProcessLlmClientTest); the pure mapping is
 * proven in ClaudeStreamParserTest.
 */
@Tag("tier1")
class ProcessLlmClientStreamTest {

    private class StreamShellClient(private val script: String) :
        ProcessLlmClient(command = "claude", defaultModel = "", workingDir = "", rateLimitRetryAfterSeconds = 300, pollMillis = 5) {
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

    private fun delta(text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}}"""
    private fun result(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","stop_reason":"end_turn"}"""

    private fun printScript(lines: List<String>) = "printf '%s\\n' " + lines.joinToString(" ") { "'$it'" }

    @Test
    fun `stream-json deltas reach the sink and the result line is the returned response`() {
        val lines = listOf(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            delta("Index"),
            delta("es help"),
            result("Indexes help"),
        )
        val client = StreamShellClient(printScript(lines))
        val (events, sink) = record()

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)

        assertEquals("Indexes help", resp.text)
        assertEquals(
            listOf(
                AguiEvent.RunStarted("n"),
                AguiEvent.TextDelta("n", "Index"),
                AguiEvent.TextDelta("n", "es help"),
                AguiEvent.RunFinished("n"),
            ),
            events,
        )
        assertTrue(client.argv.containsAll(listOf("--output-format", "stream-json", "--verbose", "--include-partial-messages")))
    }

    @Test
    fun `a failed run emits RunStarted then RunError and rethrows the taxonomy exception`() {
        val client = StreamShellClient("exit 1") // no result line, non-zero exit
        val (events, sink) = record()

        val ex = assertThrows(LlmException.ProcessError::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)
        }
        assertEquals(1, ex.exitCode)
        assertEquals(AguiEvent.RunStarted("n"), events.first())
        assertTrue(events.last() is AguiEvent.RunError)
    }
}
