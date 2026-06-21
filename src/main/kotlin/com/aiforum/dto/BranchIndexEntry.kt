package com.aiforum.dto

/**
 * One row in the thread rail's branch index — a flattened, tree-ordered jump target for a posted
 * comment. [depth] lets the rail mirror the conversation's shape with a light indent; [id] doubles
 * as the in-page anchor (the reply article carries id="reply-<id>").
 */
data class BranchIndexEntry(
    val id: String,
    val author: String,
    val depth: Int,
    // A short one-line preview of the comment body, so an entry reads "Dana — I love the id…" and
    // gives enough context to gauge the branch without overflowing the narrow rail (CSS ellipsis is
    // the second line of defence).
    val snippet: String,
)
