package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.GenerationState
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.VoteRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
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
) {
    @PostMapping("/replies/{id}/plus-one")
    fun plusOne(@PathVariable id: String, model: Model): String {
        votes.add(id, voterId = "owner")
        val comment = comments.findById(id) ?: error("no reply $id")
        model.addAttribute("replies", listOf(comment.toReplyView(voteCount = votes.count(id))))
        return "fragments/replyList"
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

    private companion object {
        const val MORE_DIRECTIVE = "/more — continue in this direction"
    }
}
