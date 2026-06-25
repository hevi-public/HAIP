package com.aiforum.agui

import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * The ONE file coupled to the AG-UI wire format. It turns our internal [AguiEvent]s into AG-UI-shaped
 * JSON (and supplies the matching event-type name used as the SSE `event:` field). Everything else in the
 * app deals only in the internal [AguiEvent] sealed type, so if AG-UI's spec moves (it's pre-1.0) or we
 * later adopt the `com.agui` dependency, this file plus [com.aiforum.tier0.AguiWireTest] are the only
 * things that change.
 *
 * Faithful-but-minimal: type strings are AG-UI's SCREAMING_SNAKE_CASE `EventType` names and fields are
 * its camelCase shapes. We deliberately simplify where the forum doesn't need fidelity — a reply is a
 * single message per run, so [AguiEvent.TextDelta] maps to TEXT_MESSAGE_CONTENT using `runId` as
 * `messageId`, and we omit AG-UI's optional `threadId`/`code`. AG-UI tolerates loose matching, and our
 * own client ([stream.js]) is the only consumer today.
 */
object AguiWire {
    private val mapper = jacksonMapperBuilder().build()

    /** The AG-UI EventType name — used both as the SSE `event:` name and inside the JSON `type` field. */
    fun type(event: AguiEvent): String = when (event) {
        is AguiEvent.RunStarted -> "RUN_STARTED"
        is AguiEvent.TextDelta -> "TEXT_MESSAGE_CONTENT"
        is AguiEvent.ToolCallStart -> "TOOL_CALL_START"
        is AguiEvent.ToolCallEnd -> "TOOL_CALL_END"
        is AguiEvent.RunFinished -> "RUN_FINISHED"
        is AguiEvent.RunError -> "RUN_ERROR"
    }

    /** The AG-UI wire JSON for one event. A LinkedHashMap keeps key order stable for the golden test. */
    fun encode(event: AguiEvent): String = mapper.writeValueAsString(fields(event))

    private fun fields(event: AguiEvent): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["type"] = type(event)
        when (event) {
            is AguiEvent.RunStarted -> m["runId"] = event.runId
            is AguiEvent.TextDelta -> { m["messageId"] = event.runId; m["delta"] = event.delta }
            is AguiEvent.ToolCallStart -> { m["toolCallId"] = event.toolCallId; m["toolCallName"] = event.name }
            is AguiEvent.ToolCallEnd -> m["toolCallId"] = event.toolCallId
            is AguiEvent.RunFinished -> m["runId"] = event.runId
            is AguiEvent.RunError -> m["message"] = event.reason
        }
        return m
    }
}
