package com.aiforum.dto

/**
 * View-model the controllers return and the JTE templates render (the frozen contract). The template
 * emits stable data-* hooks from these fields (see the jte-spring-kotlin skill).
 */
data class ReplyView(
    val id: String,
    val authorId: String,
    // Raw markdown source — kept for non-display uses (e.g. the "in reply to" quote) and assertions.
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
    // When set, the model leaked chain-of-thought into this reply; the body is already cleaned. Drives a
    // "reasoning leak" badge + the data-reasoning-leak hook on the node. Null => clean.
    val reasoningLeak: ReasoningLeak? = null,
    // True once the owner has edited this body (§7) — drives the subtle "(edited)" marker and the
    // data-edited hook. Trailing default so positional constructions stay valid.
    val edited: Boolean = false,
    // The comment this reply answers, for the "in reply to" anchor. Null for top-level replies (they
    // answer the post, which has no comment node). Populated on the full thread-page render.
    val parent: ParentRef? = null,
    val children: List<ReplyView> = emptyList(),
    // [body] rendered from GitHub-flavoured markdown to trusted HTML (see MarkdownRenderer); the template
    // emits this via $unsafe{}. Trailing default so positional constructions stay valid. Empty for
    // bodiless nodes (validation errors), which never display a body.
    val bodyHtml: String = "",
)
