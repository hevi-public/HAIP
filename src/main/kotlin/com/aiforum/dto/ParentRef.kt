package com.aiforum.dto

/**
 * The "in reply to" anchor target on a reply that answers another comment. [quote] is a LITERAL,
 * truncated, single-line excerpt of the parent's body — never an AI summary (the owner's UX ask /
 * Dana's recommendation). [id] is the parent's reply id, which doubles as the in-page anchor
 * (#reply-<id>, the same anchor the branch index jumps to).
 */
data class ParentRef(
    val id: String,
    val author: String,
    val quote: String,
) {
    companion object {
        /** Max characters of the parent body to quote before truncating with an ellipsis. */
        const val MAX_QUOTE = 100

        /** Collapse whitespace to one line and truncate to [MAX_QUOTE] chars, appending "…" if cut. */
        fun previewOf(body: String): String = Snippet.oneLine(body, MAX_QUOTE)
    }
}
