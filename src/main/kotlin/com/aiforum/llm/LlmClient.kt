package com.aiforum.llm

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
}

data class LlmRequest(
    val context: PromptContext,
    val persona: PersonaRef,
    val timeout: Duration,
)

data class LlmResponse(val text: String)

// `model` pins the LLM this persona generates with; blank => the ProcessLlmClient's default-model
// fallback. Default "" so test fixtures that don't care about model selection stay terse.
data class PersonaRef(val id: String, val name: String, val model: String = "")

/**
 * The sanitised context handed to the model. The owner's `+1` vote and the owner's human identity are
 * deliberately ABSENT here — the firewall lives at this prompt boundary, not in storage (§7/§13). The
 * acceptance suite asserts the firewall by spying on what an LlmClient actually received.
 */
data class PromptContext(
    val personaSystemPrompt: String,
    val comments: List<ContextComment>,
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
