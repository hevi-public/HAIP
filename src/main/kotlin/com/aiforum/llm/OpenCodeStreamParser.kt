package com.aiforum.llm

import com.aiforum.agui.AguiEvent
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Pure Tier-0 normalisation of `opencode run --format json` NDJSON into our [AguiEvent] vocabulary — the
 * opencode side of the provider-agnostic streaming layer (sibling of [ClaudeStreamParser] /
 * [OpenAiStreamParser]). Holds NO IO: [OpenCodeLlmClient] reads stdout line by line, feeds each raw line to
 * [onLine] (emitting deltas live), then calls [toResponse] for the authoritative classified reply.
 *
 * opencode emits one JSON object per line: `{"type":"step_start|text|step_finish|...","part":{…}}`.
 * The crucial detail: a `text` part is **cumulative** — `part.text` carries the FULL text of that part on
 * every update (parts are mutable, updated in place), keyed by `part.id`. So we track the emitted length per
 * part and emit only the new suffix as a [AguiEvent.TextDelta]; this is correct whether opencode flushes one
 * final text event or many growing ones. `step_finish.part.reason` is the finish reason.
 *
 * Stateful per run (one instance per generate call). Tool-call status is not mapped (opencode used as a
 * plain text generator for the forum); non-text parts are ignored.
 */
class OpenCodeStreamParser(private val runId: String) {

    // part.id -> chars already emitted as deltas (for suffix extraction from cumulative updates).
    private val emitted = HashMap<String, Int>()
    // part.id -> latest cumulative text, insertion-ordered so finalText() concatenates parts in order.
    private val latest = LinkedHashMap<String, String>()
    private var finishReason: String? = null
    private var errorMessage: String? = null

    /** Events to emit for one NDJSON line (empty when the line carries no streamable text). */
    fun onLine(raw: String): List<AguiEvent> {
        val line = raw.trim()
        if (line.isEmpty()) return emptyList()
        val parsed = try {
            mapper.readValue(line, Line::class.java)
        } catch (_: Exception) {
            return emptyList()
        }
        return when (parsed.type) {
            "text" -> {
                val part = parsed.part ?: return emptyList()
                val full = part.text ?: return emptyList()
                val id = part.id ?: "text"
                latest[id] = full
                val prev = emitted[id] ?: 0
                if (full.length <= prev) return emptyList() // no new text (or a shrink — guard)
                val delta = full.substring(prev)
                emitted[id] = full.length
                listOf(AguiEvent.TextDelta(runId, delta))
            }
            "step_finish" -> { finishReason = parsed.part?.reason; emptyList() }
            "error" -> {
                errorMessage = parsed.part?.error ?: parsed.part?.text ?: "opencode reported an error"
                emptyList()
            }
            else -> emptyList() // step_start, reasoning, tool, snapshot, … — not streamed to the UI
        }
    }

    /** The full reply text seen so far (all text parts concatenated in order). */
    fun finalText(): String = latest.values.joinToString("")

    /**
     * Classify the finished run into a [LlmResponse] or the right [LlmException], mirroring
     * [LlmResponseParser] / [OpenAiResponseParser]: a reported error or non-zero exit is a process fault, a
     * blank reply is empty output, a length-truncated finish is malformed, and a clean reply is sanitised
     * for leaked reasoning ([ReplySanitizer]) exactly like the other backends.
     */
    fun toResponse(exitCode: Int): LlmResponse {
        if (errorMessage != null) throw LlmException.ProcessError(if (exitCode != 0) exitCode else 1)
        if (exitCode != 0) throw LlmException.ProcessError(exitCode)
        val text = finalText()
        if (text.isBlank()) throw LlmException.EmptyOutput()
        if (finishReason == "length") throw LlmException.MalformedOutput(text)
        val sanitized = ReplySanitizer.sanitize(text)
        if (sanitized.text.isBlank()) throw LlmException.EmptyOutput()
        return LlmResponse(sanitized.text, sanitized.leak)
    }

    private companion object {
        private val mapper = jacksonMapperBuilder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }

    private data class Line(val type: String? = null, val part: Part? = null)
    private data class Part(
        val type: String? = null,
        val id: String? = null,
        val text: String? = null,
        val reason: String? = null,
        val error: String? = null,
    )
}
