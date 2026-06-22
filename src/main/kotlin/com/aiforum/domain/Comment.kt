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
    // Which content revision is currently shown (0-based, V14). 0 for an un-regenerated comment; each
    // regenerate appends a revision and points this at it. The body above is the denormalised body of
    // THIS revision, so the rest of the app reads it unchanged. Trailing default so positional
    // constructions in GenerationService keep compiling.
    val revisionIndex: Int = 0,
) {
    fun toReplyView(
        voteCount: Int = 0,
        children: List<ReplyView> = emptyList(),
        parent: ParentRef? = null,
        // Total number of stored revisions (1 when never regenerated) and whether this node can be
        // regenerated — both supplied by the assembler, which knows the persona roster and revision
        // counts. Defaulted so the bare toReplyView() calls (drafts, validation errors) stay 1-of-1.
        revisionCount: Int = 1,
        regeneratable: Boolean = false,
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
        // 0-based stored index → 1-based for display ("2/3"). The template hides the indicator unless
        // revisionCount > 1, so an un-regenerated node (1/1) shows nothing.
        revisionIndex = revisionIndex + 1,
        revisionCount = revisionCount,
        regeneratable = regeneratable,
        parent = parent,
        children = children,
    )
}
