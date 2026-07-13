package com.aiforum.llm

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Pure Tier-0 normalisation of one OpenAI Chat Completions streaming chunk (the JSON after an SSE
 * `data:` line) — the OpenAI side of the provider-agnostic streaming layer. Holds NO IO:
 * [OpenAiLlmClient] reads the SSE body line by line, hands each `data:` payload to [parseData], emits a
 * TextDelta per non-empty content chunk, and accumulates the pieces into a synthetic non-streamed envelope
 * that [OpenAiResponseParser] then classifies — so empty/length/reasoning-leak handling stays identical to
 * the non-streaming path.
 *
 * Chunk shape: `{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}` (reasoning models may
 * also stream `delta.reasoning_content` / `delta.reasoning`). The stream terminates with `data: [DONE]`.
 */
object OpenAiStreamParser {
    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** The streamable pieces of one chunk; all nullable — a chunk may carry only a role, only content, etc. */
    data class Delta(val content: String?, val reasoning: String?, val finishReason: String?)

    /** Parse one `data:` payload. Returns null for `[DONE]`, blank, or unparseable lines (ignored). */
    fun parseData(payload: String): Delta? {
        val raw = payload.trim()
        if (raw.isEmpty() || raw == "[DONE]") return null
        val chunk = try {
            mapper.readValue(raw, StreamChunk::class.java)
        } catch (_: Exception) {
            return null
        }
        val choice = chunk.choices.firstOrNull() ?: return null
        val reasoning = choice.delta?.reasoningContent ?: choice.delta?.reasoning
        return Delta(choice.delta?.content, reasoning, choice.finishReason)
    }

    private data class StreamChunk(val choices: List<StreamChoice> = emptyList())

    private data class StreamChoice(
        val delta: StreamDelta? = null,
        @param:JsonProperty("finish_reason") val finishReason: String? = null,
    )

    private data class StreamDelta(
        val content: String? = null,
        @param:JsonProperty("reasoning_content") val reasoningContent: String? = null,
        val reasoning: String? = null,
    )
}
