package com.aiforum.tier1

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.OpenAiLlmClient
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Tier-1: the streaming [OpenAiLlmClient.generate] overload — that it sends `stream: true`, normalises SSE
 * `data:` chunks into [AguiEvent]s via [com.aiforum.llm.OpenAiStreamParser], accumulates them, and routes
 * the result through [com.aiforum.llm.OpenAiResponseParser] (so empty/length/rate-limit handling is shared
 * with the blocking path). A MockRestServiceServer serves the canned SSE body; the pure per-chunk mapping
 * is proven in OpenAiStreamParserTest.
 */
@Tag("tier1")
class OpenAiLlmClientStreamTest {

    private val url = "http://localhost:1234/v1/chat/completions"

    private fun request(timeout: Duration, runId: String = "n") = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol"),
        timeout = timeout,
        runId = runId,
    )

    private fun mockClient(): Pair<OpenAiLlmClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OpenAiLlmClient(
            restClientBuilder = builder, baseUrl = "http://localhost:1234/v1", apiKey = "",
            defaultModel = "gemma", temperature = 0.7, maxTokens = 1024, pollMillis = 5, rateLimitRetryAfterSeconds = 300,
        )
        return client to server
    }

    private fun record(): Pair<MutableList<AguiEvent>, AguiEventSink> {
        val events = mutableListOf<AguiEvent>()
        return events to AguiEventSink { events.add(it) }
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("\n\n") { "data: $it" } + "\n\n"

    @Test
    fun `SSE content chunks stream as TextDeltas and accumulate into the response`() {
        val (client, server) = mockClient()
        val body = sse(
            """{"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"Index"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"es help"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("\$.stream").value(true))
            .andRespond(withSuccess(body, MediaType.TEXT_EVENT_STREAM))

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
        server.verify()
    }

    @Test
    fun `a non-2xx is classified like the blocking path and surfaces as RunError`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url)).andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("""{"error":{"message":"rate limit reached"}}""")
                .contentType(MediaType.APPLICATION_JSON),
        )
        val (events, sink) = record()

        assertThrows(LlmException.RateLimited::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)
        }
        assertEquals(AguiEvent.RunStarted("n"), events.first())
        assertTrue(events.last() is AguiEvent.RunError)
    }
}
