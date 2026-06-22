package com.aiforum.tier0

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.LlmException
import com.aiforum.llm.OpenAiResponseParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `a clean reply carries no reasoning-leak flag`() {
        assertNull(parse(200, envelope("pong")).reasoningLeak)
    }

    @Test
    fun `a think block is stripped from the content and flagged ACTUAL`() {
        val resp = parse(200, envelope("<think>plan</think>Real answer"))
        assertEquals("Real answer", resp.text)
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak)
    }

    @Test
    fun `untagged thinking preamble is kept but flagged POSSIBLE`() {
        val body = "Thinking Process: 1. Analyze the request."
        val resp = parse(200, envelope(body))
        assertEquals(body, resp.text)
        assertEquals(ReasoningLeak.POSSIBLE, resp.reasoningLeak)
    }

    @Test
    fun `content that is only a think block is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) {
            parse(200, envelope("<think>only reasoning</think>"))
        }
    }

    @Test
    fun `a split-out reasoning_content field is dropped, content is the answer, flagged ACTUAL`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":"Indexes help here.","reasoning_content":"first I weigh the index options"},""" +
            """"finish_reason":"stop"}]}"""
        val resp = parse(200, body)
        assertEquals("Indexes help here.", resp.text, "content is the clean answer")
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak, "a populated reasoning field is a dropped leak")
    }

    @Test
    fun `the alternative reasoning field is also treated as a dropped leak`() {
        val body = """{"choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":"Use a recursive CTE.","reasoning":"weighing options"},"finish_reason":"stop"}]}"""
        val resp = parse(200, body)
        assertEquals("Use a recursive CTE.", resp.text)
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak)
    }

    @Test
    fun `reasoning present but content blank is empty output`() {
        // The model put everything in the reasoning channel and gave no actual answer.
        val body = """{"choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":"","reasoning_content":"all of it went here"},"finish_reason":"stop"}]}"""
        assertThrows(LlmException.EmptyOutput::class.java) { parse(200, body) }
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
