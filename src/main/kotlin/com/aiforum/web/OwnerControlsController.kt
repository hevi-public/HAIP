package com.aiforum.web

import com.aiforum.repo.CommentRepository
import com.aiforum.repo.VoteRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping

/**
 * Owner controls (§7). `+1` is the firewalled vote: recorded and shown to the owner, never fed to the
 * model. (`/more` — the visible depth-granting directive — is deferred to the team behind this
 * contract; see depth_budget.feature.)
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
}
