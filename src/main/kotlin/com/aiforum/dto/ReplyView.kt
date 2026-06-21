package com.aiforum.dto

/**
 * View-model the controllers return and the JTE templates render (the frozen contract). The template
 * emits stable data-* hooks from these fields (see the jte-spring-kotlin skill).
 */
data class ReplyView(
    val id: String,
    val authorId: String,
    val body: String,
    val state: GenerationState,
    val failureCategory: FailureCategory?,
    val reason: String?,
    val retryable: Boolean,
    val retryAfterSeconds: Long?,
    val voteCount: Int,
    val depth: Int,
    val depthBudget: Int = 0,
    // Owner's star bookmark — drives the filled-star control on the node and the star marker in the rail.
    val starred: Boolean = false,
    // The comment this reply answers, for the "in reply to" anchor. Null for top-level replies (they
    // answer the post, which has no comment node). Populated on the full thread-page render.
    val parent: ParentRef? = null,
    val children: List<ReplyView> = emptyList(),
)
