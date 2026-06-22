package com.aiforum.domain

import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ParentRef
import com.aiforum.dto.ReasoningLeak
import com.aiforum.dto.ReplyView
import com.aiforum.markdown.MarkdownRenderer
import java.time.Instant

/** Persisted comment-tree node. */
data class Comment(
    val id: String,
    val threadId: String,
    val parentId: String?,
    val authorId: String,
    val body: String,
    val state: GenerationState,
    val failureCategory: FailureCategory?,
    val depth: Int,
    val reason: String? = null,
    val retryAfterSeconds: Long? = null,
    // Per-branch autonomous-growth fuel (§4). Trailing field with a default so the positional
    // constructions in GenerationService keep compiling; budget-setting call sites pass it by name.
    val depthBudget: Int = 0,
    // Owner's navigation bookmark (§7) — firewalled from the model like +1, never fed into a prompt.
    // Trailing default so positional constructors keep compiling; the star endpoint toggles it.
    val starred: Boolean = false,
    // Set when the model leaked chain-of-thought into this reply (ReplySanitizer): the body is already
    // cleaned, this only drives the UI badge. Trailing default so positional constructors keep compiling.
    val reasoningLeak: ReasoningLeak? = null,
    // When the owner last edited this body (§7), or null if never edited (V11). Drives the "(edited)"
    // marker on the node; only the edit endpoint stamps it, so a generation settle never sets it.
    val updatedAt: Instant? = null,
) {
    fun toReplyView(
        voteCount: Int = 0,
        children: List<ReplyView> = emptyList(),
        parent: ParentRef? = null,
    ) = ReplyView(
        id = id,
        authorId = authorId,
        body = body,
        bodyHtml = MarkdownRenderer.render(body),
        state = state,
        failureCategory = failureCategory,
        reason = reason,
        retryable = state == GenerationState.FAILED || state == GenerationState.CANCELLED,
        retryAfterSeconds = retryAfterSeconds,
        voteCount = voteCount,
        depth = depth,
        depthBudget = depthBudget,
        starred = starred,
        reasoningLeak = reasoningLeak,
        edited = updatedAt != null,
        parent = parent,
        children = children,
    )
}
