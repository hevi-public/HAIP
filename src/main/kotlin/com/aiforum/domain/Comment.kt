package com.aiforum.domain

import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView

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
) {
    fun toReplyView(voteCount: Int = 0, children: List<ReplyView> = emptyList()) = ReplyView(
        id = id,
        authorId = authorId,
        body = body,
        state = state,
        failureCategory = failureCategory,
        reason = reason,
        retryable = state == GenerationState.FAILED || state == GenerationState.CANCELLED,
        retryAfterSeconds = retryAfterSeconds,
        voteCount = voteCount,
        depth = depth,
        children = children,
    )
}
