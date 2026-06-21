package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.dto.BranchIndexEntry
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ParentRef
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.dto.Snippet
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.repo.VoteRepository
import com.aiforum.service.GenerationService
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
 * Thread-level endpoints: create a thread and view its page. Creating a thread immediately summons the
 * room — a "Whole Topic + Anyone" call: the AI dispatcher reads the opening post and picks who weighs in,
 * then the chosen persona(s) reply (§2). The summon is async, so the page surfaces the in-flight drafts
 * (which self-poll to settle) rather than the old "waiting on the room" empty state.
 */
@Controller
class ThreadController(
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val votes: VoteRepository,
    private val threadReads: ThreadReadRepository,
    private val generation: GenerationService,
) {

    // Two bindings, one creation path: the browser's new-thread form posts form-urlencoded and wants a
    // PRG redirect onto the fresh thread page; the acceptance suite / API client posts JSON and asserts
    // on the returned thread HTML. Both go through [newThread] so the behaviour can't drift.
    @PostMapping("/threads", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createJson(@RequestBody req: CreateThreadRequest, model: Model): String {
        val id = newThread(req.title, req.text)
        return renderThread(id, req.title, req.text, model)
    }

    @PostMapping("/threads", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun createForm(req: CreateThreadRequest): String {
        val id = newThread(req.title, req.text)
        // Post/Redirect/Get: land the browser on the new thread (correct URL, refresh-safe), where the
        // room — summoned on create (see newThread) — is already drafting its replies.
        return "redirect:/threads/$id"
    }

    // text is the opening post's body — split out from the title on the new-thread form (§2). Optional;
    // blank for the title-only API/browser paths.
    private fun newThread(title: String, body: String): String {
        val id = UUID.randomUUID().toString()
        threads.insert(id, title, body)
        // Summon the room on creation: a "Whole Topic + Anyone" call. AUTO_PERSONA is the "Anyone"
        // sentinel (the AI dispatcher picks who replies); WHOLE_THREAD for both scopes is "Whole Topic"
        // — the dispatcher reads the whole topic (the opening post) to route, and the chosen persona then
        // reads the whole topic too. No owner message to post (the opening post lives on the thread body
        // and seeds context via the OP node), so postAsOwner stays false. Async — settles on a worker
        // thread while the request returns; the drafts surface on the thread page and self-poll.
        generation.startGeneration(
            threadId = id,
            parentId = null,
            personaIds = listOf(GenerationService.AUTO_PERSONA),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
        )
        return id
    }

    @GetMapping("/threads/{id}")
    fun view(@PathVariable id: String, model: Model): String {
        val thread = threads.find(id) ?: return "redirect:/"
        threadReads.markRead(id)
        return renderThread(thread.id, thread.title, thread.body, model)
    }

    private fun renderThread(id: String, title: String, body: String, model: Model): String {
        val all = comments.threadComments(id)
        model.addAttribute("threadId", id)
        model.addAttribute("title", title)
        model.addAttribute("body", body)
        // Nest replies under their parents so the page reflects the comment tree (a persona reply sits
        // under the message it answered). replyNode.kte renders reply.children recursively; the flat
        // list it gets here was rendering every node at level 0. Children keep their repository order
        // (depth, created_at), so siblings stay chronological.
        val tree = assembleTree(all)
        // The room is summoned on creation (async); its DRAFTING replies live only in the in-flight
        // registry until they settle — no DRAFTING DB row exists. Surface them at the top level so a plain
        // page load (e.g. the PRG redirect after create) shows the room responding: each drafting node
        // self-polls /replies/{id} and settles in place. Dedupe by id against the DB tree to avoid a double
        // render in the brief window after a draft's settle-write but before it's evicted from in-flight.
        val rendered = collectIds(tree)
        val drafting = generation.inFlightViews(id).filter { it.id !in rendered }
        model.addAttribute("replies", tree + drafting)
        // Persona views carry each persona's stored colour slot, so the branch-index dots resolve to the
        // same hue as the reply monograms (see AuthorColor).
        val personaViews = personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }
        // Branch index for the side rail: the posted nodes flattened in the same depth-first order the
        // page renders them, so the rail reads top-to-bottom alongside the thread. Drafting nodes are not
        // posted, so they stay out of the rail until they settle.
        model.addAttribute("branchIndex", branchIndex(tree, personaViews))
        // "Waiting on the room" only when nothing has posted AND nothing is drafting — i.e. the room
        // hasn't been summoned (no personas to route to). With the create-time summon there are normally
        // drafts in flight, so a fresh thread shows the room responding rather than an empty wait.
        model.addAttribute("waitingOnRoom", all.none { it.state == GenerationState.POSTED } && drafting.isEmpty())
        model.addAttribute("personas", personaViews)
        return "thread"
    }

    /** Every reply id in the rendered tree (all depths) — so surfaced in-flight drafts aren't double-rendered. */
    private fun collectIds(tree: List<ReplyView>): Set<String> {
        val ids = mutableSetOf<String>()
        fun walk(node: ReplyView) {
            ids += node.id
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return ids
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
