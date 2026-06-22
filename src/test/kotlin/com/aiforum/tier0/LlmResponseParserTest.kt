package com.aiforum.tier0

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmResponseParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-0: the pure mapping from a finished `claude -p` invocation to the failure taxonomy. No
 * subprocess, no Spring — every branch is driven by a canned (exitCode, stdout) pair. The success
 * fixture is the real envelope captured from `claude -p --output-format json`.
 */
@Tag("tier0")
class LlmResponseParserTest {

    private val retryAfter = Duration.ofSeconds(300)

    private fun parse(exitCode: Int, stdout: String) =
        LlmResponseParser.parse(exitCode, stdout, retryAfter)

    @Test
    fun `a clean reply carries no reasoning-leak flag`() {
        val envelope = """{"is_error":false,"subtype":"success","result":"pong","stop_reason":"end_turn"}"""
        assertNull(parse(0, envelope).reasoningLeak)
    }

    @Test
    fun `a think block is stripped from the result and flagged ACTUAL`() {
        val envelope = """{"is_error":false,"subtype":"success","result":"<think>plan</think>Real answer","stop_reason":"end_turn"}"""
        val resp = parse(0, envelope)
        assertEquals("Real answer", resp.text)
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak)
    }

    @Test
    fun `untagged thinking preamble is kept but flagged POSSIBLE`() {
        val body = "Thinking Process: 1. Analyze the request."
        val envelope = """{"is_error":false,"subtype":"success","result":"$body","stop_reason":"end_turn"}"""
        val resp = parse(0, envelope)
        assertEquals(body, resp.text)
        assertEquals(ReasoningLeak.POSSIBLE, resp.reasoningLeak)
    }

    @Test
    fun `a result that is only a think block is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"<think>only reasoning</think>","stop_reason":"end_turn"}""")
        }
    }

    @Test
    fun `the real success envelope yields its result text`() {
        val envelope = """
            {"type":"result","subtype":"success","is_error":false,"api_error_status":null,
             "duration_ms":3255,"num_turns":1,"result":"pong","stop_reason":"end_turn",
             "session_id":"abc","total_cost_usd":0.14}
        """.trimIndent()
        assertEquals("pong", parse(0, envelope).text)
    }

    @Test
    fun `a clean exit with non-zero code is a process error even with output`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            parse(2, """{"is_error":false,"subtype":"success","result":"hi","stop_reason":"end_turn"}""")
        }
        assertEquals(2, ex.exitCode)
    }

    @Test
    fun `empty stdout with zero exit is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) { parse(0, "   ") }
    }

    @Test
    fun `empty stdout with non-zero exit is a process error`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) { parse(137, "") }
        assertEquals(137, ex.exitCode)
    }

    @Test
    fun `a blank result in a success envelope is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"","stop_reason":"end_turn"}""")
        }
    }

    @Test
    fun `a truncated reply (max_tokens) is malformed output`() {
        assertThrows(LlmException.MalformedOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"half a th","stop_reason":"max_tokens"}""")
        }
    }

    @Test
    fun `non-JSON stdout is malformed output carrying the raw text`() {
        val ex = assertThrows(LlmException.MalformedOutput::class.java) { parse(0, "not json at all") }
        assertEquals("not json at all", ex.raw)
    }

    @Test
    fun `a usage-limit error envelope is rate-limited with the configured retry-after`() {
        val ex = assertThrows(LlmException.RateLimited::class.java) {
            parse(1, """{"is_error":true,"subtype":"error","api_error_status":429,"result":"usage limit reached"}""")
        }
        assertEquals(retryAfter, ex.retryAfter)
    }

    @Test
    fun `a successful reply that merely mentions rate limits is not mistaken for one`() {
        // A forum reply about API rate limiting must not false-positive into RATE_LIMITED — rate
        // detection is scoped to error envelopes only.
        val body = "To avoid hitting the rate limit, batch your writes."
        val envelope = """{"is_error":false,"subtype":"success","result":"$body","stop_reason":"end_turn"}"""
        assertEquals(body, parse(0, envelope).text)
    }

    @Test
    fun `a generic error envelope with a non-zero exit is a process error`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            parse(1, """{"is_error":true,"subtype":"error_during_execution","result":"boom"}""")
        }
        assertEquals(1, ex.exitCode)
    }
}
