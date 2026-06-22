package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.GenerationState
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.VoteRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.util.UUID

/**
 * Owner controls (§7). `+1` is the firewalled vote: recorded and shown to the owner, never fed to the
 * model. The two depth-granting controls live here too: an owner reply and the visible `/more`
 * directive both re-grant a branch's autonomous depth budget (§4).
 */
@Controller
class OwnerControlsController(
    private val votes: VoteRepository,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val replyTree: ReplyTreeAssembler,
) {

    /**
     * Edit a posted comment body (§7) — the owner fixing their own wording, or correcting an AI persona's
     * reply when it misread the context (the corrected text then seeds future summons in this branch). The
     * inline edit form (a collapsed disclosure on the node) outerHTML-swaps the closest <article> with the
     * re-rendered node, so we return the WHOLE subtree (replyTree.subtree) — a bare node would drop the
     * children nested inside this article. Only POSTED nodes are editable and a blank body is rejected; in
     * either case we re-render the node unchanged so the swap restores it cleanly.
     */
    @PostMapping("/replies/{id}/edit")
    fun edit(
        @PathVariable id: String,
        @RequestParam(required = false) text: String?,
        model: Model,
    ): String {
        val comment = comments.findById(id) ?: error("no reply $id")
        if (comment.state == GenerationState.POSTED && !text.isNullOrBlank()) {
            comments.editBody(id, text.trim())
        }
        val node = replyTree.subtree(id) ?: comment.toReplyView()
        model.addAttribute("reply", node)
        model.addAttribute("threadId", comment.threadId)
        model.addAttribute("personas", personaViews())
        return "fragments/replyNode"
    }

    private fun personaViews(): List<PersonaView> =
        personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }
    @PostMapping("/replies/{id}/plus-one")
    fun plusOne(@PathVariable id: String, model: Model): String {
        votes.add(id, voterId = "owner")
        val comment = comments.findById(id) ?: error("no reply $id")
        model.addAttribute("reply", comment.toReplyView(voteCount = votes.count(id)))
        return "fragments/voteArea"
    }

    /**
     * Star / unstar a comment — the owner's navigation bookmark, firewalled from the model like `+1`.
     * Toggles the persisted flag and re-renders just the star control (its own htmx swap target), so
     * the button reflects the new state in place. The branch-index rail mirrors the star client-side
     * (nav.js reads each star area's data-starred), so the rail marker flips without a full reload.
     */
    @PostMapping("/replies/{id}/star")
    fun star(@PathVariable id: String, model: Model): String {
        comments.toggleStar(id)
        val comment = comments.findById(id) ?: error("no reply $id")
        model.addAttribute("reply", comment.toReplyView(voteCount = votes.count(id)))
        return "fragments/starControl"
    }

    /**
     * The owner comments on a branch. The owner is just another member to the personas (§2), so this is
     * an ordinary node — but as an owner comment it re-grants the branch's depth budget (§4), so
     * autonomous growth resumes there (and only there).
     */
    @PostMapping("/replies/{parentId}/owner-reply")
    fun ownerReply(
        @PathVariable parentId: String,
        @RequestParam(required = false) text: String?,
        model: Model,
    ): String {
        val parent = comments.findById(parentId) ?: error("no node $parentId")
        val node = Comment(
            id = UUID.randomUUID().toString(),
            threadId = parent.threadId,
            parentId = parentId,
            authorId = "owner",
            body = text ?: "Let's keep going.",
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parent.depth + 1,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(node)
        model.addAttribute("replies", listOf(node.toReplyView()))
        return "fragments/replyList"
    }

    /**
     * `/more` — the explicit, on-the-record steering directive (§7). Unlike the firewalled `+1`, the
     * directive itself IS handed to the model, so it is persisted as a branch node and flows into the
     * generation context; but the caller is anonymised (author "system", never "owner"). It also
     * re-grants the branch's depth budget so the tangent keeps growing without the owner composing a
     * reply (§4).
     */
    @PostMapping("/replies/{parentId}/more")
    fun more(@PathVariable parentId: String, model: Model): String {
        val parent = comments.findById(parentId) ?: error("no node $parentId")
        val directive = Comment(
            id = UUID.randomUUID().toString(),
            threadId = parent.threadId,
            parentId = parentId,
            authorId = "system",
            body = MORE_DIRECTIVE,
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parent.depth + 1,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(directive)
        model.addAttribute("replies", listOf(directive.toReplyView()))
        return "fragments/replyList"
    }

    /**
     * Delete a comment and its whole subtree (§8 node operations). The browser button outerHTML-swaps the closest
     * <article> with this empty response, so the node — and its replies, which render nested inside it —
     * vanish from the DOM in one swap, mirroring the cascade the repository performs in the DB.
     */
    @PostMapping("/replies/{id}/delete")
    @ResponseBody
    fun delete(@PathVariable id: String): String {
        comments.deleteSubtree(id)
        return ""
    }

    private companion object {
        const val MORE_DIRECTIVE = "/more — continue in this direction"
    }
}
