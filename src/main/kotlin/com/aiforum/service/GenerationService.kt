package com.aiforum.service

import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.domain.lifecycle.GenerationStateMachine
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Orchestrates generation through the single LlmClient seam (Tier 2 running real Tier-0/1 below it,
 * see the bdd-tiered-testing skill). Sequential fan-out for M1: each persona generates in turn, and
 * one persona failing does not abort the others (partial-roomful).
 */
@Service
class GenerationService(
    private val llm: LlmClient,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
) {
    private val timeout = Duration.ofSeconds(120)

    fun generate(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        @Suppress("UNUSED_PARAMETER") text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
    ): List<ReplyView> {
        val baseDepth = parentId?.let { (comments.findById(it)?.depth ?: 0) + 1 } ?: 0
        // The context differentiator (§5): branch-only = root→parent ancestor path (recursive CTE);
        // whole-thread = the full tree. Branch-only excludes siblings unless the owner opts them in,
        // in which case the reply target's siblings (the other children of its parent) are added.
        val contextComments = if (scope == ScopeMode.BRANCH_ONLY && parentId != null) {
            val path = comments.ancestorPath(parentId)
            if (includeSiblings) {
                val parentOfTarget = comments.findById(parentId)?.parentId
                (path + comments.childrenOf(parentOfTarget)).distinctBy { it.id }
            } else {
                path
            }
        } else {
            comments.threadComments(threadId)
        }
        // sequential (M1): map preserves order; a failure becomes a FAILED node, not an abort
        return personaIds.map { personaId -> runOne(threadId, parentId, personaId, baseDepth, contextComments) }
    }

    fun retry(replyId: String): ReplyView {
        val existing = comments.findById(replyId) ?: error("no reply $replyId")
        val persona = personas.find(existing.authorId) ?: error("unknown persona ${existing.authorId}")
        val ctx = ContextAssembler.assemble(persona.systemPrompt, comments.threadComments(existing.threadId))
        val updated = try {
            val resp = llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name), timeout), CancellationToken())
            existing.copy(body = resp.text, state = GenerationState.POSTED, failureCategory = null, reason = null, retryAfterSeconds = null)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            existing.copy(body = "", state = o.state, failureCategory = o.failureCategory, reason = o.reason, retryAfterSeconds = o.retryAfterSeconds)
        }
        comments.update(updated)
        return updated.toReplyView()
    }

    private fun runOne(threadId: String, parentId: String?, personaId: String, depth: Int, contextComments: List<Comment>): ReplyView {
        val persona = personas.find(personaId) ?: error("unknown persona $personaId")
        val ctx = ContextAssembler.assemble(persona.systemPrompt, contextComments)
        val id = UUID.randomUUID().toString()
        val comment = try {
            val resp = llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name), timeout), CancellationToken())
            Comment(id, threadId, parentId, personaId, resp.text, GenerationState.POSTED, null, depth)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            Comment(id, threadId, parentId, personaId, "", o.state, o.failureCategory, depth, o.reason, o.retryAfterSeconds)
        }
        return persist(comment)
    }

    /**
     * Persist the node. If the write fails (UX state E, §4) the generation itself already succeeded, so
     * the drafted body must NOT be lost: we keep it, surface COULDNT_SAVE, and persist a failure marker
     * (the write fault is a one-shot transient blip) so the owner can retry from a real row.
     */
    private fun persist(comment: Comment): ReplyView = try {
        comments.insert(comment)
        comment.toReplyView()
    } catch (e: Throwable) {
        val marker = comment.copy(
            state = GenerationState.FAILED,
            failureCategory = FailureCategory.COULDNT_SAVE,
            reason = "couldn't save — draft kept",
            retryAfterSeconds = null,
        )
        comments.insert(marker)
        marker.toReplyView()
    }
}
