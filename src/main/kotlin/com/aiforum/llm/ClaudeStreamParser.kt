package com.aiforum.llm

import com.aiforum.agui.AguiEvent
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Pure Tier-0 normalisation of `claude -p --output-format stream-json` NDJSON into our [AguiEvent]
 * vocabulary — the claude side of the provider-agnostic streaming layer (sibling of [LlmResponseParser],
 * which still classifies the FINAL result). Holds NO IO: [ProcessLlmClient] reads stdout line by line and
 * feeds each raw line to [onLine], emitting whatever comes back, then hands the captured [resultJson] to
 * [LlmResponseParser] for the authoritative response — so the persisted reply is byte-identical to the
 * non-streaming path; the deltas are purely for liveness.
 *
 * Stateful per run (one instance per generate call): it tracks open tool-use blocks by content-block index
 * to pair start/stop, and suppresses a trailing full `assistant` message once token deltas have been seen
 * (claude emits both with `--include-partial-messages`) so text isn't shown twice.
 *
 * Assumed shapes (Claude Code stream-json):
 *  - partial text: `{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}}`
 *  - tool start:   `{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_x","name":"WebFetch"}}}`
 *  - tool stop:    `{"type":"stream_event","event":{"type":"content_block_stop","index":1}}`
 *  - whole message (no partial): `{"type":"assistant","message":{"content":[{"type":"text","text":"…"}]}}`
 *  - terminal:     `{"type":"result","subtype":"success","is_error":false,"result":"…","stop_reason":"end_turn"}`
 */
class ClaudeStreamParser(private val runId: String) {

    /** The raw terminal `result` line, captured for [LlmResponseParser]; "" until one is seen. */
    var resultJson: String = ""
        private set

    private val toolByIndex = HashMap<Int, String>()
    private var sawTextDelta = false

    /** Events to emit for one NDJSON line (empty when the line carries no streamable signal). */
    fun onLine(raw: String): List<AguiEvent> {
        val line = raw.trim()
        if (line.isEmpty()) return emptyList()
        val parsed = try {
            mapper.readValue(line, StreamLine::class.java)
        } catch (_: Exception) {
            return emptyList() // a non-JSON line (banner, blank) is just ignored
        }

        if (parsed.type == "result") {
            resultJson = line
            return emptyList()
        }

        // A full assistant message arrives in non-partial mode (or as a trailing summary in partial mode).
        // Emit its text only when we haven't already streamed token deltas, so text is never doubled.
        if (parsed.type == "assistant") {
            if (sawTextDelta) return emptyList()
            val text = parsed.message?.content.orEmpty()
                .filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
            return if (text.isEmpty()) emptyList() else listOf(AguiEvent.TextDelta(runId, text))
        }

        val ev = if (parsed.type == "stream_event") parsed.event else null
        return when (ev?.type) {
            "content_block_delta" -> {
                val text = ev.delta?.text
                if (text.isNullOrEmpty()) emptyList()
                else { sawTextDelta = true; listOf(AguiEvent.TextDelta(runId, text)) }
            }
            "content_block_start" -> {
                val block = ev.contentBlock
                if (block?.type != "tool_use") emptyList()
                else {
                    val id = block.id ?: "tool-${ev.index ?: 0}"
                    ev.index?.let { toolByIndex[it] = id }
                    listOf(AguiEvent.ToolCallStart(runId, id, block.name ?: "tool"))
                }
            }
            "content_block_stop" -> {
                val id = ev.index?.let { toolByIndex.remove(it) }
                if (id == null) emptyList() else listOf(AguiEvent.ToolCallEnd(runId, id))
            }
            else -> emptyList()
        }
    }

    private companion object {
        private val mapper = jacksonMapperBuilder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }

    private data class StreamLine(
        val type: String? = null,
        val event: AnthropicEvent? = null,
        val message: AssistantMessage? = null,
    )

    private data class AnthropicEvent(
        val type: String? = null,
        val index: Int? = null,
        val delta: AnthropicDelta? = null,
        @param:JsonProperty("content_block") val contentBlock: ContentBlock? = null,
    )

    private data class AnthropicDelta(val type: String? = null, val text: String? = null)
    private data class ContentBlock(val type: String? = null, val id: String? = null, val name: String? = null)
    private data class AssistantMessage(val content: List<ContentPart> = emptyList())
    private data class ContentPart(val type: String? = null, val text: String? = null)
}
