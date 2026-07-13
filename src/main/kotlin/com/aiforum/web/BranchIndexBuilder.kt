package com.aiforum.web

import com.aiforum.dto.BranchIndexEntry
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Component

/** How many characters of a comment to preview in a branch-index entry (CSS ellipsis caps the rest). */
private const val BRANCH_SNIPPET_LEN = 48

/**
 * Builds the thread rail's branch index — the posted nodes flattened depth-first into the rail's jump
 * list. Shared by the full thread render (which already has the assembled tree) and by every fragment
 * response that changes the posted set (a reply posting, a draft settling, an edit, a delete), so the
 * rail can be refreshed as an htmx out-of-band swap and never drifts from the page. Drafting nodes are
 * not posted, so they stay out of the rail until they settle.
 */
@Component
class BranchIndexBuilder(
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val replyTree: ReplyTreeAssembler,
) {

    /** Load the thread, assemble its tree, and flatten the posted nodes — for fragment OOB refreshes. */
    fun forThread(threadId: String): List<BranchIndexEntry> =
        fromTree(replyTree.assemble(comments.threadComments(threadId)), personaViews())

    /** Flatten an already-assembled tree depth-first into the rail's jump list, posted nodes only. */
    fun fromTree(tree: List<ReplyView>, personaViews: List<PersonaView>): List<BranchIndexEntry> {
        val out = mutableListOf<BranchIndexEntry>()
        fun walk(node: ReplyView) {
            if (node.state == GenerationState.POSTED) {
                out += BranchIndexEntry(
                    node.id, node.authorId, node.depth,
                    Snippet.oneLine(node.body, BRANCH_SNIPPET_LEN),
                    AuthorColor.hue(node.authorId, personaViews),
                    starred = node.starred,
                )
            }
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return out
    }

    /** Persona views carry each persona's colour slot, so the rail dots match the reply monograms. */
    fun personaViews(): List<PersonaView> =
        personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }
}
