package com.aiforum.llm

import com.aiforum.dto.ReasoningLeak
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Duration

/**
 * Pure Tier-0 classification of a finished OpenAI Chat Completions call into either a successful
 * [LlmResponse] or the right [LlmException] (see the bdd-tiered-testing skill). The sibling of
 * [LlmResponseParser] for the HTTP backend: it holds NO IO — the socket plumbing, timeout and
 * cancellation live in [OpenAiLlmClient] — so every branch of the failure taxonomy is unit-testable
 * against a canned (status, body, Retry-After) triple.
 *
 * It maps onto the SAME [LlmException] taxonomy the CLI path uses, so the generation lifecycle (§4) is
 * provider-agnostic. The only deliberately-CLI-flavoured reuse is [LlmException.ProcessError], whose
 * `exitCode` field carries the HTTP status for an upstream/server fault.
 */
object OpenAiResponseParser {
    // Lenient on unknown fields (the real envelope carries usage, id, created, …); the Kotlin module
    // applies defaults to the ones we omit, so a sparse error body deserialises cleanly.
    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Substrings (lower-cased) that mark a non-2xx body as a usage/rate limit rather than a generic fault. */
    private val RATE_LIMIT_SIGNALS = listOf(
        "rate limit", "rate_limit", "ratelimit", "usage limit", "overloaded", "too many requests", "quota",
    )

    /** The subset of the Chat Completions envelope we classify on. */
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
    )

    private data class Choice(
        val message: Message? = null,
        @param:JsonProperty("finish_reason") val finishReason: String? = null,
    )

    // Reasoning models served over the OpenAI API may split their chain-of-thought into a dedicated
    // field — DeepSeek's `reasoning_content`, or `reasoning` on some servers — leaving `content` as the
    // clean answer. We read both so a populated one can be dropped (and flagged) rather than shown.
    private data class Message(
        val content: String? = null,
        @param:JsonProperty("reasoning_content") val reasoningContent: String? = null,
        val reasoning: String? = null,
    )

    /**
     * @param status the HTTP status code of the response (or the transport's best equivalent).
     * @param body the raw response body.
     * @param retryAfterHeader the `Retry-After` header value, if any (seconds form honoured; else fallback).
     * @param configuredRetryAfter the fallback backoff when the server gives no usable Retry-After.
     */
    fun parse(status: Int, body: String, retryAfterHeader: String?, configuredRetryAfter: Duration): LlmResponse {
        val raw = body.trim()

        if (status !in 200..299) {
            // Rate-limit detection is scoped to error responses only, so a successful reply that merely
            // *mentions* "rate limit" in its body is never mistaken for one. 429 is the canonical signal;
            // some OpenAI-compatible servers surface usage caps as a 4xx with a textual marker instead.
            val signal = raw.lowercase()
            if (status == 429 || RATE_LIMIT_SIGNALS.any { it in signal }) {
                throw LlmException.RateLimited(retryAfter(retryAfterHeader, configuredRetryAfter))
            }
            // Any other non-2xx (incl. a connection-refused mapped to 0 by the client) is an upstream fault.
            throw LlmException.ProcessError(status)
        }

        if (raw.isEmpty()) throw LlmException.EmptyOutput()

        val resp = try {
            mapper.readValue(raw, ChatResponse::class.java)
        } catch (_: Exception) {
            // 2xx but not the JSON envelope we asked for — truncated or malformed.
            throw LlmException.MalformedOutput(raw)
        }

        val choice = resp.choices.firstOrNull() ?: throw LlmException.EmptyOutput()
        val message = choice.message
        val content = message?.content.orEmpty()
        // The server split reasoning into its own field: `content` is the clean answer and we DROP the
        // reasoning, but its presence is a definite leak (the persona was told not to reason aloud), so we
        // flag it ACTUAL — the clean-separation path that needs no regex. Blank content with reasoning
        // present means the model put EVERYTHING in the reasoning channel and gave no answer → empty.
        val splitReasoning = !message?.reasoningContent.isNullOrBlank() || !message?.reasoning.isNullOrBlank()
        if (content.isBlank()) throw LlmException.EmptyOutput()
        // Hit the output ceiling mid-reply: we have text but it's truncated, so the owner should retry —
        // the HTTP analogue of the CLI's stop_reason == "max_tokens".
        if (choice.finishReason == "length") throw LlmException.MalformedOutput(content)
        // Also strip any reasoning that leaked INLINE into content (tagged or untagged) — both can happen,
        // even alongside a split field. Never discard; flag what we strip/suspect (see ReplySanitizer).
        val sanitized = ReplySanitizer.sanitize(content)
        if (sanitized.text.isBlank()) throw LlmException.EmptyOutput()
        // A split-out reasoning field is certain, so it wins over the inline heuristic's verdict.
        val leak = if (splitReasoning) ReasoningLeak.ACTUAL else sanitized.leak
        return LlmResponse(sanitized.text, leak)
    }

    /** `Retry-After` is seconds-or-HTTP-date; we honour the seconds form and fall back to config otherwise. */
    private fun retryAfter(header: String?, fallback: Duration): Duration =
        header?.trim()?.toLongOrNull()?.let { Duration.ofSeconds(it) } ?: fallback
}
