package com.aiforum.domain.context

import com.aiforum.llm.ContextComment

/**
 * Renders the sanitised [ContextComment] list into the flat transcript the model reads. Pure (no Spring,
 * no IO) so it lives here rather than inside the Tier-1 [com.aiforum.llm.ProcessLlmClient] seam and gets
 * pinned by a Tier-0 test.
 *
 * Thread shape is conveyed two ways, deliberately redundant: indentation (by depth, normalised to the
 * shallowest comment in scope so branch-only ancestor paths don't run off the page) gives an at-a-glance
 * picture, while the "↳ replying to #n" tag is the load-bearing, whitespace-independent signal — it
 * survives even if the CLI trims leading space. Bodies are flattened to one line so the grid stays
 * legible. Kept plain text (not JSON): cheaper in tokens and closer to how the model read threads in
 * training.
 *
 * [targetId] (when in scope) gets a "← reply to this" marker: in whole-thread scope the transcript is the
 * whole tree ordered by depth/created_at, so the node the owner is replying to is rarely the last line —
 * the marker (and the matching ref in the prompt) names it so the model doesn't answer an unrelated branch.
 */
object TranscriptRenderer {
    /** The suffix marking the node the persona is being summoned to reply to. The prompt names the same ref. */
    const val TARGET_MARKER = " ← reply to this"

    fun render(comments: List<ContextComment>, targetId: String? = null): String {
        // Stable short ref per comment, in transcript order, so reply tags disambiguate repeated authors.
        val refOf = comments.withIndex().associate { (i, c) -> c.id to (i + 1) }
        val base = comments.minOfOrNull { it.depth } ?: 0
        return comments.joinToString("\n") { c ->
            val indent = "  ".repeat((c.depth - base).coerceAtLeast(0))
            val ref = refOf.getValue(c.id)
            val replyTag = c.parentId?.let { refOf[it] }?.let { " ↳ replying to #$it" } ?: ""
            val targetTag = if (c.id == targetId) TARGET_MARKER else ""
            val body = c.body.replace("\n", " ").trim()
            "$indent[#$ref$replyTag] ${c.authorId}: $body$targetTag"
        }
    }

    /** The 1-based transcript ref of [targetId], or null when it isn't in scope — so the prompt can name it. */
    fun refOf(comments: List<ContextComment>, targetId: String?): Int? =
        targetId?.let { id -> comments.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { it + 1 } }
}
