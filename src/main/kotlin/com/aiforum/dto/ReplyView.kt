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
    val children: List<ReplyView> = emptyList(),
)
