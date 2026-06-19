package com.aiforum.domain.lifecycle

import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.llm.LlmException

/** The outcome of classifying a generation failure into a UX state (§4). */
data class Outcome(
    val state: GenerationState,
    val failureCategory: FailureCategory?,
    val reason: String,
    val retryable: Boolean,
    val retryAfterSeconds: Long?,
)

/**
 * Pure Tier-0 mapping from a thrown failure to its visible state/category (see the bdd-tiered-testing
 * skill). This is exactly what the single Tier-1 seam lets the acceptance suite simulate.
 */
object GenerationStateMachine {
    fun classify(error: Throwable): Outcome = when (error) {
        is LlmException.Timeout ->
            Outcome(GenerationState.FAILED, FailureCategory.FAILED_RETRY, "timed out before any output", true, null)
        is LlmException.ProcessError ->
            Outcome(GenerationState.FAILED, FailureCategory.FAILED_RETRY, "process exited ${error.exitCode}", true, null)
        is LlmException.EmptyOutput ->
            Outcome(GenerationState.FAILED, FailureCategory.FAILED_RETRY, "empty output — nothing generated", true, null)
        is LlmException.MalformedOutput ->
            Outcome(GenerationState.FAILED, FailureCategory.FAILED_RETRY, "output truncated/malformed", true, null)
        is LlmException.RateLimited ->
            Outcome(GenerationState.FAILED, FailureCategory.RATE_LIMITED, "usage limit reached — rate-limited", true, error.retryAfter.seconds)
        is LlmException.Cancelled ->
            Outcome(GenerationState.CANCELLED, FailureCategory.CANCELLED, "you stopped this draft", true, null)
        else ->
            Outcome(GenerationState.FAILED, FailureCategory.FAILED_RETRY, error.message ?: "generation failed", true, null)
    }
}
