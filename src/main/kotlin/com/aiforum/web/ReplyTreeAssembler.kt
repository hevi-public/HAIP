package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.dto.ParentRef
import com.aiforum.dto.ReplyView
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.VoteRepository
import org.springframework.stereotype.Component

/**
 * Nests a flat thread list into the reply tree the page renders (a persona reply sits under the message
 * it answered). Shared by the full thread render and by single-node re-renders (an edit) that must keep
 * a node's whole subtree intact through an htmx outerHTML swap.
 */
@Component
class ReplyTreeAssembler(
    private val comments: CommentRepository,
    private val votes: VoteRepository,
) {

    /** Build the top-level reply views with their descendants nested, from the flat thread list. */
    fun assemble(all: List<Comment>): List<ReplyView> {
        val voteCounts = votes.countAll()
        val childrenByParent = all.groupBy { it.parentId }
        val byId = all.associateBy { it.id }
        // The "in reply to" anchor only earns its place when a reply is visually separated from the
        // comment it answers. A parent's FIRST child renders immediately under it (depth-first preorder),
        // so the quote would just echo the line above — redundant clutter. Later siblings get pushed
        // down past the first child's whole sub-thread, so the anchor re-establishes "who am I answering"
        // (the owner's UX ask). isDirect = "renders right under its parent" = is the parent's first child.
        fun build(comment: Comment, isDirect: Boolean): ReplyView =
            comment.toReplyView(
                voteCount = voteCounts[comment.id] ?: 0,
                children = childrenByParent[comment.id].orEmpty()
                    .mapIndexed { index, child -> build(child, isDirect = index == 0) },
                // Null for top-level nodes (parentId null — they answer the post, which has no comment
                // node) and for direct replies (the parent is the line directly above).
                parent = if (isDirect) null else comment.parentId?.let { byId[it] }?.let {
                    ParentRef(it.id, it.authorId, ParentRef.previewOf(it.body))
                },
            )
        // Top-level nodes answer the post, not a comment — treat them as direct so they carry no anchor.
        return childrenByParent[null].orEmpty().map { build(it, isDirect = true) }
    }

    /**
     * The single node [nodeId] rendered with its descendants nested, exactly as it appears on the thread
     * page (same in-reply-to anchor, same children) — for an htmx outerHTML swap that must preserve the
     * subtree (an edit re-render). Null if the node doesn't exist. Re-uses the full-thread assembly so a
     * re-rendered node can never drift from how the page builds it.
     */
    fun subtree(nodeId: String): ReplyView? {
        val node = comments.findById(nodeId) ?: return null
        var found: ReplyView? = null
        fun walk(v: ReplyView) {
            if (v.id == nodeId) { found = v; return }
            v.children.forEach(::walk)
        }
        assemble(comments.threadComments(node.threadId)).forEach(::walk)
        return found
    }
}
