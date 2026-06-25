package com.aiforum.llm

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.dto.ReasoningLeak
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single Tier-1 IO seam for generation (see the bdd-tiered-testing skill). Everything above it
 * runs real code against this one boundary; under the `test` profile a scriptable fake stands in.
 * Production wraps `claude -p` via ProcessBuilder, honouring the cancellation token by killing the
 * subprocess.
 */
interface LlmClient {
    fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse

    /**
     * Streaming overload: identical contract to [generate], but [sink] receives [AguiEvent]s as the reply
     * is produced — RunStarted, then incremental TextDelta(s) / tool-call status, then a terminal
     * RunFinished or RunError. The aggregate [LlmResponse] is still returned (and persisted) so the
     * settle/poll/render path is unchanged.
     *
     * The DEFAULT wraps the blocking [generate], emitting the whole reply as one TextDelta, so a
     * non-streaming backend — and the test double — satisfies the streaming path for free. Backends that
     * can stream ([ProcessLlmClient], [OpenAiLlmClient]) override this to emit real per-token deltas. This
     * keeps the SINGLE test seam: callers always go through `generate`, just with or without a sink.
     */
    fun generate(request: LlmRequest, cancellation: CancellationToken, sink: AguiEventSink): LlmResponse {
        sink.emit(AguiEvent.RunStarted(request.runId))
        try {
            val response = generate(request, cancellation)
            if (response.text.isNotEmpty()) sink.emit(AguiEvent.TextDelta(request.runId, response.text))
            sink.emit(AguiEvent.RunFinished(request.runId))
            return response
        } catch (e: Throwable) {
            sink.emit(AguiEvent.RunError(request.runId, e.message ?: "generation failed"))
            throw e
        }
    }
}

data class LlmRequest(
    val context: PromptContext,
    val persona: PersonaRef,
    val timeout: Duration,
    // The in-flight node id this generation settles into — used as the AG-UI `runId`/`messageId` on every
    // emitted event so the stream can be routed to the right drafting node. Default "" keeps the
    // non-streaming call sites (retry/regenerate) and terse test fixtures positional.
    val runId: String = "",
)

// `reasoningLeak` tags a reply whose model leaked chain-of-thought (set by ReplySanitizer in the
// parsers). The body is already cleaned; this only drives the UI badge + a log line. Null => clean.
// Trailing default so the scriptable test double and other LlmResponse("text") call sites stay terse.
data class LlmResponse(val text: String, val reasoningLeak: ReasoningLeak? = null)

// `model` pins the LLM this persona generates with; blank => the ProcessLlmClient's default-model
// fallback. Default "" so test fixtures that don't care about model selection stay terse.
data class PersonaRef(val id: String, val name: String, val model: String = "")

/**
 * The sanitised context handed to the model. The owner's `+1` vote and the owner's human identity are
 * deliberately ABSENT here — the firewall lives at this prompt boundary, not in storage (§7/§13). The
 * acceptance suite asserts the firewall by spying on what an LlmClient actually received.
 */
// `targetId` is the comment the persona is being summoned to reply to — the owner's freshly-posted
// message, the leaf being auto-grown, or (on retry) the original parent. It is the id of one of the
// [comments], used by renderPrompt to mark "← reply to this" and name the ref explicitly, so the model
// answers the intended node rather than guessing "the most recent line" (which, in whole-thread scope,
// is whatever sorts last by depth/created_at — not the node the owner clicked). Null only when there is
// no specific target (opening a new thread), in which case renderPrompt falls back to "most recent".
data class PromptContext(
    val personaSystemPrompt: String,
    val comments: List<ContextComment>,
    val targetId: String? = null,
)

// `parentId`/`depth` are structural-only: they let renderPrompt show the model the reply shape
// (indentation + "↳ replying to #n" tags). Still no vote/owner signal — the firewall is unchanged.
data class ContextComment(
    val id: String,
    val authorId: String,
    val body: String,
    val parentId: String?,
    val depth: Int,
)

/** Cooperative cancellation: the fake checks it; production maps it to process.destroyForcibly(). */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() = cancelled.set(true)
}

/** The failure taxonomy the generation lifecycle maps onto UX states (§4). */
sealed class LlmException(message: String) : RuntimeException(message) {
    class Timeout : LlmException("generation timed out")
    class ProcessError(val exitCode: Int) : LlmException("claude -p exited $exitCode")
    class RateLimited(val retryAfter: Duration) : LlmException("rate-limited")
    class EmptyOutput : LlmException("empty output")
    class MalformedOutput(val raw: String) : LlmException("malformed/truncated output")
    class Cancelled : LlmException("cancelled by owner")
}
