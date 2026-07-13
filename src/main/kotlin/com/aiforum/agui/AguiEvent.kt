package com.aiforum.agui

/**
 * The provider-agnostic streaming event vocabulary, modelled on AG-UI's stable core
 * (https://github.com/ag-ui-protocol/ag-ui). These are OUR internal types — every backend (claude -p,
 * OpenAI, a future opencode) normalises its native stream into this one shape, and the whole app speaks
 * it. The AG-UI WIRE serialisation (the bit that's actually coupled to the spec) is isolated in
 * [AguiWire]; nothing else here or upstream knows the on-the-wire JSON, so a spec bump — or a decision to
 * take the `com.agui` dependency — touches that one file plus its Tier-0 test.
 *
 * This is deliberately a MINIMAL subset (YAGNI): a forum reply is a single message per run, so we don't
 * model TextMessageStart/End, tool args/results, or state snapshots/deltas. [TextDelta] carries the
 * incremental text; the tool events are status-only (the UI just shows "calling X…"). Add more only when
 * a concrete need appears.
 *
 * `runId` is the in-flight node id (the comment id being drafted) — the same id the SSE endpoint
 * `/replies/{id}/stream` and [com.aiforum.service.InFlightGenerations] key on.
 */
sealed interface AguiEvent {
    val runId: String

    /** A generation has begun (AG-UI RUN_STARTED). */
    data class RunStarted(override val runId: String) : AguiEvent

    /** An incremental chunk of reply text (AG-UI TEXT_MESSAGE_CONTENT `delta`). */
    data class TextDelta(override val runId: String, val delta: String) : AguiEvent

    /** A persona started a tool call (AG-UI TOOL_CALL_START); status-only, drives the "calling X…" line. */
    data class ToolCallStart(override val runId: String, val toolCallId: String, val name: String) : AguiEvent

    /** That tool call finished (AG-UI TOOL_CALL_END). */
    data class ToolCallEnd(override val runId: String, val toolCallId: String) : AguiEvent

    /** Generation completed successfully (AG-UI RUN_FINISHED). Terminal. */
    data class RunFinished(override val runId: String) : AguiEvent

    /** Generation failed (AG-UI RUN_ERROR). [reason] is a short human string. Terminal. */
    data class RunError(override val runId: String, val reason: String) : AguiEvent

    /** True for the two terminal events — after one of these no further events follow for a run. */
    val isTerminal: Boolean get() = this is RunFinished || this is RunError
}

/**
 * Where a producer pushes [AguiEvent]s as it generates. The generation worker hands one of these to the
 * [com.aiforum.llm.LlmClient] streaming overload; in tests a recording sink captures the sequence.
 */
fun interface AguiEventSink {
    fun emit(event: AguiEvent)
}
