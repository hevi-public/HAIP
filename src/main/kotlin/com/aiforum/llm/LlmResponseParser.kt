package com.aiforum.llm

import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Pure Tier-0 classification of a finished `claude -p --output-format json` invocation into either a
 * successful [LlmResponse] or the right [LlmException] (see the bdd-tiered-testing skill). This holds
 * NO IO — the subprocess plumbing lives in [ProcessLlmClient]; here we only reason about the captured
 * (exitCode, stdout) pair, so every branch of the failure taxonomy is unit-testable against canned
 * envelopes.
 *
 * The envelope shape is the CLI's documented `result` JSON, e.g.
 * `{"type":"result","subtype":"success","is_error":false,"api_error_status":null,
 *   "result":"pong","stop_reason":"end_turn",...}`.
 */
object LlmResponseParser {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    /** Substrings (lower-cased) that mark an error envelope as a usage/rate limit rather than a generic fault. */
    private val RATE_LIMIT_SIGNALS = listOf(
        "rate limit", "rate_limit", "ratelimit", "usage limit", "overloaded", "429", "too many requests",
    )

    fun parse(exitCode: Int, stdout: String, rateLimitRetryAfter: Duration): LlmResponse {
        val raw = stdout.trim()
        if (raw.isEmpty()) {
            // Nothing on stdout at all: a non-zero exit is a hard process failure, otherwise the model
            // simply produced nothing — both are FAILED_RETRY but the taxonomy distinguishes them.
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            throw LlmException.EmptyOutput()
        }

        val node = try {
            mapper.readTree(raw)
        } catch (_: Exception) {
            // stdout present but not the JSON envelope we asked for — truncated or malformed.
            throw LlmException.MalformedOutput(raw)
        }

        val isError = node.path("is_error").asBoolean(false)
        val subtype = node.path("subtype").asText("")
        val result = node.path("result").asText("")
        val stopReason = node.path("stop_reason").asText("")
        val errored = isError || (subtype.isNotEmpty() && subtype != "success")

        if (errored) {
            // Rate-limit detection is scoped to error envelopes only, so a successful reply that merely
            // *mentions* "rate limit" in its body is never mistaken for one. The signal can surface in the
            // structured api_error_status or in the error text the CLI puts in `result`.
            val signal = (subtype + " " + node.path("api_error_status").toString() + " " + result).lowercase()
            if (RATE_LIMIT_SIGNALS.any { it in signal }) throw LlmException.RateLimited(rateLimitRetryAfter)
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            // An error envelope with a clean exit and no rate signal: nothing usable came back.
            throw LlmException.MalformedOutput(raw)
        }

        // Success envelope. A non-zero exit alongside it is contradictory — trust the exit code.
        if (exitCode != 0) throw LlmException.ProcessError(exitCode)
        if (result.isBlank()) throw LlmException.EmptyOutput()
        // Hit the output ceiling mid-reply: we have text but it's truncated, so the owner should retry.
        if (stopReason == "max_tokens") throw LlmException.MalformedOutput(result)
        return LlmResponse(result)
    }
}
