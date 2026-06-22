package com.aiforum.tier0

import com.aiforum.llm.LlmException
import com.aiforum.llm.OpenAiResponseParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-0: the pure mapping from a finished OpenAI Chat Completions call to the failure taxonomy — the
 * HTTP sibling of [com.aiforum.tier0.LlmResponseParserTest]. No socket, no Spring: every branch is driven
 * by a canned (status, body, Retry-After) triple, mapped onto the SAME [LlmException] types the CLI uses.
 */
@Tag("tier0")
class OpenAiResponseParserTest {

    private val retryAfter = Duration.ofSeconds(300)

    private fun parse(status: Int, body: String, retryAfterHeader: String? = null) =
        OpenAiResponseParser.parse(status, body, retryAfterHeader, retryAfter)

    private fun envelope(content: String, finishReason: String = "stop") =
        """{"id":"x","object":"chat.completion","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"$content"},"finish_reason":"$finishReason"}],""" +
            """"usage":{"total_tokens":7}}"""

    @Test
    fun `a success envelope yields the assistant message content`() {
        assertEquals("pong", parse(200, envelope("pong")).text)
    }

    @Test
    fun `a 2xx with no choices is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) { parse(200, """{"id":"x","choices":[]}""") }
    }

    @Test
    fun `a 2xx with an empty body is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) { parse(200, "   ") }
    }

    @Test
    fun `a blank assistant message is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) { parse(200, envelope("   ")) }
    }

    @Test
    fun `a length-truncated reply is malformed output`() {
        assertThrows(LlmException.MalformedOutput::class.java) {
            parse(200, envelope("half a th", finishReason = "length"))
        }
    }

    @Test
    fun `2xx non-JSON body is malformed output carrying the raw text`() {
        val ex = assertThrows(LlmException.MalformedOutput::class.java) { parse(200, "not json at all") }
        assertEquals("not json at all", ex.raw)
    }

    @Test
    fun `a 429 is rate-limited with the configured retry-after when no header is given`() {
        val ex = assertThrows(LlmException.RateLimited::class.java) {
            parse(429, """{"error":{"message":"Rate limit reached"}}""")
        }
        assertEquals(retryAfter, ex.retryAfter)
    }

    @Test
    fun `a 429 honours a numeric Retry-After header over the configured fallback`() {
        val ex = assertThrows(LlmException.RateLimited::class.java) {
            parse(429, """{"error":{"message":"slow down"}}""", retryAfterHeader = "30")
        }
        assertEquals(Duration.ofSeconds(30), ex.retryAfter)
    }

    @Test
    fun `a non-429 body that signals a usage quota is still rate-limited`() {
        assertThrows(LlmException.RateLimited::class.java) {
            parse(400, """{"error":{"message":"You exceeded your current quota"}}""")
        }
    }

    @Test
    fun `a 500 is a process error carrying the status`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            parse(500, """{"error":{"message":"internal"}}""")
        }
        assertEquals(500, ex.exitCode)
    }

    @Test
    fun `a transport failure (status 0) is a process error`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) { parse(0, "") }
        assertEquals(0, ex.exitCode)
    }

    @Test
    fun `a successful reply that merely mentions rate limits is not mistaken for one`() {
        // Rate detection is scoped to non-2xx responses, so a 200 reply about rate limiting is just text.
        val body = "To avoid hitting the rate limit, batch your writes."
        assertEquals(body, parse(200, envelope(body)).text)
    }
}
