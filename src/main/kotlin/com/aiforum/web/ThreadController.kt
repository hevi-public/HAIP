package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.dto.BranchIndexEntry
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ParentRef
import com.aiforum.dto.ReplyView
import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.repo.VoteRepository
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** How many characters of a comment to preview in a branch-index entry (CSS ellipsis caps the rest). */
private const val BRANCH_SNIPPET_LEN = 48

/** Request body for POST /threads. */
data class CreateThreadRequest(
    val title: String = "",
    val text: String = "",
    val personaIds: List<String> = emptyList(),
)

/**
 * Thread-level endpoints: create a thread and view its page. Generation is triggered separately
 * via GenerationController; the fresh thread shows the "waiting on the room" empty state (§2).
 */
@Controller
class ThreadController(
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val votes: VoteRepository,
    private val threadReads: ThreadReadRepository,
) {

    // Two bindings, one creation path: the browser's new-thread form posts form-urlencoded and wants a
    // PRG redirect onto the fresh thread page; the acceptance suite / API client posts JSON and asserts
    // on the returned thread HTML. Both go through [newThread] so the behaviour can't drift.
    @PostMapping("/threads", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createJson(@RequestBody req: CreateThreadRequest, model: Model): String {
        return renderThread(newThread(req.title, req.text), model)
    }

    @PostMapping("/threads", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun createForm(req: CreateThreadRequest): String {
        val thread = newThread(req.title, req.text)
        // Post/Redirect/Get: land the browser on the new thread (correct URL, refresh-safe), where the
        // bottom composer is waiting to ask the room.
        return "redirect:/threads/${thread.id}"
    }

    // The opening question [text] is the post's body — it must NOT be dropped here (without it the
    // owner's words vanish "on the way in" and the room only ever sees a blank transcript). Blank → null
    // (the browser quick-create posts a title only). The post stays the thread-backed root node.
    private fun newThread(title: String, text: String): ThreadRepository.Thread {
        val id = UUID.randomUUID().toString()
        val body = text.trim().ifBlank { null }
        threads.insert(id, title, body)
        return ThreadRepository.Thread(id, title, body)
    }

    @GetMapping("/threads/{id}")
    fun view(@PathVariable id: String, model: Model): String {
        val thread = threads.find(id) ?: return "redirect:/"
        threadReads.markRead(id)
        return renderThread(thread, model)
    }

    private fun renderThread(thread: ThreadRepository.Thread, model: Model): String {
        val id = thread.id
        val all = comments.threadComments(id)
        model.addAttribute("threadId", id)
        model.addAttribute("title", thread.title)
        // The opening post renders on the OP node (thread__op), under the title.
        model.addAttribute("body", thread.body)
        // Nest replies under their parents so the page reflects the comment tree (a persona reply sits
        // under the message it answered). replyNode.kte renders reply.children recursively; the flat
        // list it gets here was rendering every node at level 0. Children keep their repository order
        // (depth, created_at), so siblings stay chronological.
        val tree = assembleTree(all)
        model.addAttribute("replies", tree)
        // Persona views carry each persona's stored colour slot, so the branch-index dots resolve to the
        // same hue as the reply monograms (see AuthorColor).
        val personaViews = personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }
        // Branch index for the side rail: the posted nodes flattened in the same depth-first order the
        // page renders them, so the rail reads top-to-bottom alongside the thread. Empty until the room
        // has spoken, which keeps a fresh thread single-column (the aside stays hidden).
        model.addAttribute("branchIndex", branchIndex(tree, personaViews))
        model.addAttribute("waitingOnRoom", all.none { it.state == GenerationState.POSTED })
        model.addAttribute("personas", personaViews)
        return "thread"
    }

    /** Flatten the reply tree depth-first into the rail's jump list, posted nodes only. */
    private fun branchIndex(tree: List<ReplyView>, personas: List<PersonaView>): List<BranchIndexEntry> {
        val out = mutableListOf<BranchIndexEntry>()
        fun walk(node: ReplyView) {
            if (node.state == GenerationState.POSTED) {
                out += BranchIndexEntry(
                    node.id, node.authorId, node.depth,
                    Snippet.oneLine(node.body, BRANCH_SNIPPET_LEN), AuthorColor.hue(node.authorId, personas),
                )
            }
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return out
    }

    /** Build the top-level reply views with their descendants nested, from the flat thread list. */
    private fun assembleTree(all: List<Comment>): List<ReplyView> {
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
}
