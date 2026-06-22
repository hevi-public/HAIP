package com.aiforum.llm

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Duration

/**
 * Pure Tier-0 classification of a finished `claude -p --output-format json` invocation into either a
 * successful [LlmResponse] or the right [LlmException] (see the bdd-tiered-testing skill). This holds
 * NO IO — the subprocess plumbing lives in [ProcessLlmClient]; here we only reason about the captured
 * (exitCode, stdout) pair, so every branch of the failure taxonomy is unit-testable against canned
 * envelopes.
 */
object LlmResponseParser {
    // Lenient on unknown fields (the real envelope carries ~20); the Kotlin module applies defaults to
    // the ones we omit, so a sparse error envelope deserialises cleanly.
    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Substrings (lower-cased) that mark an error envelope as a usage/rate limit rather than a generic fault. */
    private val RATE_LIMIT_SIGNALS = listOf(
        "rate limit", "rate_limit", "ratelimit", "usage limit", "overloaded", "429", "too many requests",
    )

    /**
     * The subset of the CLI's `result` JSON we classify on, e.g.
     * `{"subtype":"success","is_error":false,"api_error_status":null,"result":"pong","stop_reason":"end_turn"}`.
     * `apiErrorStatus` is `Any?` because the CLI puts a null / number / object there depending on the
     * fault — we only ever stringify it to scan for a rate-limit signal.
     */
    private data class ClaudeEnvelope(
        @param:JsonProperty("is_error") val isError: Boolean = false,
        val subtype: String? = null,
        val result: String? = null,
        @param:JsonProperty("stop_reason") val stopReason: String? = null,
        @param:JsonProperty("api_error_status") val apiErrorStatus: Any? = null,
    )

    fun parse(exitCode: Int, stdout: String, rateLimitRetryAfter: Duration): LlmResponse {
        val raw = stdout.trim()
        if (raw.isEmpty()) {
            // Nothing on stdout at all: a non-zero exit is a hard process failure, otherwise the model
            // simply produced nothing — both are FAILED_RETRY but the taxonomy distinguishes them.
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            throw LlmException.EmptyOutput()
        }

        val env = try {
            mapper.readValue(raw, ClaudeEnvelope::class.java)
        } catch (_: Exception) {
            // stdout present but not the JSON envelope we asked for — truncated or malformed.
            throw LlmException.MalformedOutput(raw)
        }

        val subtype = env.subtype.orEmpty()
        val result = env.result.orEmpty()
        val errored = env.isError || (subtype.isNotEmpty() && subtype != "success")

        if (errored) {
            // Rate-limit detection is scoped to error envelopes only, so a successful reply that merely
            // *mentions* "rate limit" in its body is never mistaken for one. The signal can surface in the
            // structured api_error_status or in the error text the CLI puts in `result`.
            val signal = (subtype + " " + env.apiErrorStatus?.toString().orEmpty() + " " + result).lowercase()
            if (RATE_LIMIT_SIGNALS.any { it in signal }) throw LlmException.RateLimited(rateLimitRetryAfter)
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            // An error envelope with a clean exit and no rate signal: nothing usable came back.
            throw LlmException.MalformedOutput(raw)
        }

        // Success envelope. A non-zero exit alongside it is contradictory — trust the exit code.
        if (exitCode != 0) throw LlmException.ProcessError(exitCode)
        if (result.isBlank()) throw LlmException.EmptyOutput()
        // Hit the output ceiling mid-reply: we have text but it's truncated, so the owner should retry.
        if (env.stopReason == "max_tokens") throw LlmException.MalformedOutput(result)
        // Strip any leaked chain-of-thought and flag what we strip/suspect (never discard — see
        // ReplySanitizer). A body that was ONLY a <think> block is now blank: that's empty output.
        val sanitized = ReplySanitizer.sanitize(result)
        if (sanitized.text.isBlank()) throw LlmException.EmptyOutput()
        return LlmResponse(sanitized.text, sanitized.leak)
    }
}
