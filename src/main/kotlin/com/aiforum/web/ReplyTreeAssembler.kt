package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.dto.AttachmentView
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ParentRef
import com.aiforum.dto.QuoteBacklink
import com.aiforum.dto.QuoteQuoter
import com.aiforum.dto.QuoteRef
import com.aiforum.dto.ReplyView
import com.aiforum.dto.Snippet
import com.aiforum.repo.AttachmentRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.QuoteRepository
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
    private val personas: PersonaRepository,
    private val attachments: AttachmentRepository,
    private val quotes: QuoteRepository,
) {

    /** Build the top-level reply views with their descendants nested, from the flat thread list. */
    fun assemble(all: List<Comment>): List<ReplyView> {
        val voteCounts = votes.countAll()
        val childrenByParent = all.groupBy { it.parentId }
        val byId = all.associateBy { it.id }
        // Regenerate is offered only on a POSTED reply authored by a persona (not the owner or the system
        // /more directive). Revision counts are read once for the whole thread; a comment with no stored
        // revisions is an implicit 1-of-1, so the node shows no switcher (template gates on count > 1).
        val personaIds = personas.findAll().map { it.id }.toSet()
        val revisionCounts = all.firstOrNull()?.let { comments.revisionCountsByComment(it.threadId) } ?: emptyMap()
        // One batch read for the whole tree's images (no per-node query), folded into each node below.
        val attByComment = attachments.forComments(all.map { it.id })
        // One batch read for the thread's quote edges, grouped both ways: by the quoting (src) comment for
        // each node's forward "quotes" strip, and by the quoted (target) comment for its "quoted by"
        // backlinks. Empty thread => no edges. (edgesIn reads once; the two groupings are in-memory.)
        val threadId = all.firstOrNull()?.threadId
        val quotesBySrc = threadId?.let { quotes.bySource(it) } ?: emptyMap()
        val quotesByTarget = threadId?.let { quotes.byTarget(it) } ?: emptyMap()
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
                revisionCount = (revisionCounts[comment.id] ?: 0).coerceAtLeast(1),
                regeneratable = comment.state == GenerationState.POSTED && comment.authorId in personaIds,
                attachments = attByComment[comment.id].orEmpty().map(AttachmentView::of),
                // Forward quote refs: this comment's edges, resolved to the target's author + a literal
                // snippet of the snapshot. A target that somehow isn't in the tree is dropped (mapNotNull).
                quotes = quotesBySrc[comment.id].orEmpty().mapNotNull { edge ->
                    byId[edge.targetCommentId]?.let { t ->
                        QuoteRef(t.id, t.authorId, QuoteRef.previewOf(edge.quotedText))
                    }
                },
                // Backward backlinks: incoming edges grouped by the exact quoted passage (per-exact-span
                // coalescing), each carrying its quoters (resolved to author + a snippet of the quoter's
                // own body). Group order follows edge order (oldest first); a quoter not in the tree is
                // dropped, and a passage left with no quoters is dropped.
                quotedBy = quotesByTarget[comment.id].orEmpty()
                    .groupBy { it.quotedText }
                    .map { (text, edges) ->
                        QuoteBacklink(
                            text,
                            edges.mapNotNull { e ->
                                byId[e.srcCommentId]?.let { s ->
                                    QuoteQuoter(s.id, s.authorId, Snippet.oneLine(s.body, 80))
                                }
                            },
                        )
                    }
                    .filter { it.quoters.isNotEmpty() },
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
