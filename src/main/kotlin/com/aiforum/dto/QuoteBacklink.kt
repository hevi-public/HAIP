package com.aiforum.dto

/**
 * The backward direction of the quote graph (plan_docs/comment-quotes.md §3/§6): one distinct quoted
 * passage of a comment and the comments that quoted it. Coalescing is **per exact span** — all quoters of
 * the identical [text] collapse into one [QuoteBacklink], so the source comment shows one inline mark per
 * passage whose hover "cone" lists every [quoters] entry.
 *
 * [text] is the verbatim snapshot, carried in full to the client (data-backlink-text) so quote-backlinks.js
 * can re-find and highlight the passage in the rendered body; the server never wraps it (the body HTML and
 * its XSS firewall stay untouched — the mark is added client-side).
 */
data class QuoteBacklink(
    val text: String,
    val quoters: List<QuoteQuoter>,
)

/** A comment that quoted a passage: links to #reply-[commentId] in the cone, labelled by [author] with a
 *  one-line [snippet] of the quoter's own body so the reader can tell the citations apart. */
data class QuoteQuoter(
    val commentId: String,
    val author: String,
    val snippet: String,
)
