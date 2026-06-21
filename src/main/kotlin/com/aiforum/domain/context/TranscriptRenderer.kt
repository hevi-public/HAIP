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
 */
object TranscriptRenderer {
    fun render(comments: List<ContextComment>): String {
        // Stable short ref per comment, in transcript order, so reply tags disambiguate repeated authors.
        val refOf = comments.withIndex().associate { (i, c) -> c.id to (i + 1) }
        val base = comments.minOfOrNull { it.depth } ?: 0
        return comments.joinToString("\n") { c ->
            val indent = "  ".repeat((c.depth - base).coerceAtLeast(0))
            val ref = refOf.getValue(c.id)
            val replyTag = c.parentId?.let { refOf[it] }?.let { " ↳ replying to #$it" } ?: ""
            val body = c.body.replace("\n", " ").trim()
            "$indent[#$ref$replyTag] ${c.authorId}: $body"
        }
    }
}
