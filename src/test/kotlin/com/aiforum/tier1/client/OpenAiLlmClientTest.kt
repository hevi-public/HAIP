package com.aiforum.tier1.client

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.OpenAiLlmClient
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tier-1: the genuinely un-fakeable plumbing of [OpenAiLlmClient] — request shaping (model selection,
 * system/user messages, bearer auth), HTTP status → taxonomy mapping, the timeout deadline, and
 * cooperative cancellation. Response classification is driven by a `MockRestServiceServer` bound to the
 * injected builder; the deadline/cancel/connection-refused paths use a real local socket (the HTTP
 * analogue of ProcessLlmClientTest's `/bin/sh` subprocess). The pure (status, body) mapping is proven
 * separately in OpenAiResponseParserTest.
 */
@Tag("tier1")
class OpenAiLlmClientTest {

    private val url = "http://localhost:1234/v1/chat/completions"

    private fun request(timeout: Duration, personaModel: String = "") = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol", personaModel),
        timeout = timeout,
    )

    private fun envelope(content: String, finishReason: String = "stop") =
        """{"id":"x","object":"chat.completion","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"$content"},"finish_reason":"$finishReason"}]}"""

    /** A client whose HTTP goes to a MockRestServiceServer (for response/request-shape assertions). */
    private fun mockClient(apiKey: String = "", defaultModel: String = "gemma"): Pair<OpenAiLlmClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OpenAiLlmClient(
            restClientBuilder = builder,
            baseUrl = "http://localhost:1234/v1",
            apiKey = apiKey,
            defaultModel = defaultModel,
            temperature = 0.7,
            maxTokens = 1024,
            pollMillis = 5,
            rateLimitRetryAfterSeconds = 300,
        )
        return client to server
    }

    /** A client pointed at a real (absolute) base URL, for the deadline/cancel/refused paths. */
    private fun realClient(baseUrl: String, pollMillis: Long = 5) = OpenAiLlmClient(
        restClientBuilder = RestClient.builder(),
        baseUrl = baseUrl,
        apiKey = "",
        defaultModel = "gemma",
        temperature = 0.7,
        maxTokens = 1024,
        pollMillis = pollMillis,
        rateLimitRetryAfterSeconds = 300,
    )

    @Test
    fun `a successful chat completion returns the assistant message content`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("\$.messages[0].role").value("system"))
            .andExpect(jsonPath("\$.messages[0].content").value("you are sol"))
            .andExpect(jsonPath("\$.messages[1].role").value("user"))
            .andExpect(jsonPath("\$.max_tokens").value(1024))
            .andRespond(withSuccess(envelope("indexes it is"), MediaType.APPLICATION_JSON))

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken())

        assertEquals("indexes it is", resp.text)
        server.verify()
    }

    @Test
    fun `a persona's pinned model is sent and wins over the configured default`() {
        val (client, server) = mockClient(defaultModel = "gemma")
        server.expect(requestTo(url))
            .andExpect(jsonPath("\$.model").value("llama"))
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10), personaModel = "llama"), CancellationToken())
        server.verify()
    }

    @Test
    fun `a persona with no pinned model falls back to the configured default-model`() {
        val (client, server) = mockClient(defaultModel = "gemma")
        server.expect(requestTo(url))
            .andExpect(jsonPath("\$.model").value("gemma"))
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        server.verify()
    }

    @Test
    fun `a set api key is sent as a bearer token`() {
        val (client, server) = mockClient(apiKey = "secret")
        server.expect(requestTo(url))
            .andExpect(header("Authorization", "Bearer secret"))
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        server.verify()
    }

    @Test
    fun `a blank api key sends no Authorization header`() {
        val (client, server) = mockClient(apiKey = "")
        server.expect(requestTo(url))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        server.verify()
    }

    @Test
    fun `disable-thinking sends chat_template_kwargs to turn reasoning off at generation time`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = OpenAiLlmClient(
            restClientBuilder = builder, baseUrl = "http://localhost:1234/v1", apiKey = "",
            defaultModel = "gemma", temperature = 0.7, maxTokens = 1024, pollMillis = 5,
            rateLimitRetryAfterSeconds = 300, disableThinking = true,
        )
        server.expect(requestTo(url))
            .andExpect(jsonPath("\$.chat_template_kwargs.enable_thinking").value(false))
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        server.verify()
    }

    @Test
    fun `by default no chat_template_kwargs is sent`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url))
            .andExpect(jsonPath("\$.chat_template_kwargs").doesNotExist())
            .andRespond(withSuccess(envelope("ok"), MediaType.APPLICATION_JSON))

        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        server.verify()
    }

    @Test
    fun `a 429 maps to RateLimited`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url)).andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("""{"error":{"message":"rate limit reached"}}""")
                .contentType(MediaType.APPLICATION_JSON),
        )
        assertThrows(LlmException.RateLimited::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
    }

    @Test
    fun `a 500 maps to ProcessError carrying the status`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url)).andRespond(withServerError().body("boom"))
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
        assertEquals(500, ex.exitCode)
    }

    @Test
    fun `a length-truncated reply maps to MalformedOutput`() {
        val (client, server) = mockClient()
        server.expect(requestTo(url))
            .andRespond(withSuccess(envelope("half a th", finishReason = "length"), MediaType.APPLICATION_JSON))
        assertThrows(LlmException.MalformedOutput::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
    }

    @Test
    fun `a server that never responds is abandoned at the deadline and surfaces Timeout`() {
        val hang = ServerSocket(0)
        val held = CopyOnWriteArrayList<Socket>()
        // Accept connections and hold them open without ever responding; keep references so the accepted
        // sockets aren't GC'd-and-closed (which would look like a dropped connection, not a hang).
        Thread { runCatching { while (true) held.add(hang.accept()) } }.apply { isDaemon = true }.start()
        try {
            val client = realClient("http://localhost:${hang.localPort}/v1")
            assertThrows(LlmException.Timeout::class.java) {
                client.generate(request(Duration.ofMillis(200)), CancellationToken())
            }
        } finally {
            hang.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `tripping the cancellation token mid-call surfaces Cancelled`() {
        val hang = ServerSocket(0)
        val held = CopyOnWriteArrayList<Socket>()
        Thread { runCatching { while (true) held.add(hang.accept()) } }.apply { isDaemon = true }.start()
        try {
            val token = CancellationToken()
            Thread { Thread.sleep(50); token.cancel() }.apply { isDaemon = true }.start()
            val client = realClient("http://localhost:${hang.localPort}/v1")
            assertThrows(LlmException.Cancelled::class.java) {
                client.generate(request(Duration.ofSeconds(30)), token)
            }
        } finally {
            hang.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `an unreachable server surfaces ProcessError`() {
        // Grab a port then free it, so nothing is listening => connection refused.
        val free = ServerSocket(0)
        val port = free.localPort
        free.close()
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            realClient("http://localhost:$port/v1").generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
        assertEquals(0, ex.exitCode)
    }
}
