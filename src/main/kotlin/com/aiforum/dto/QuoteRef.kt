package com.aiforum.dto

/**
 * The forward "quotes" anchor on a comment that cites another (see plan_docs/comment-quotes.md). Links to
 * the source COMMENT via #reply-<targetId> — the same in-page anchor the branch index and the in-reply-to
 * quote use. [snippet] is a LITERAL one-line preview of the quoted span (Snippet.oneLine), never an AI
 * summary — mirrors [ParentRef]. Generalises the structural in-reply-to anchor to arbitrary cross-links.
 */
data class QuoteRef(
    val targetId: String,
    val targetAuthor: String,
    val snippet: String,
) {
    companion object {
        /** Max characters of the quoted span to preview before truncating (mirrors ParentRef). */
        const val MAX_SNIPPET = 100

        /** Collapse the snapshot to one line and truncate to [MAX_SNIPPET], appending "…" if cut. */
        fun previewOf(quotedText: String): String = Snippet.oneLine(quotedText, MAX_SNIPPET)
    }
}
