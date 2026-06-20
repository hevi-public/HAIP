package com.aiforum.service

import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
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

    private companion object {
        // Runaway backstop for autoGrow; real growth always drains in ≤ DepthBudget.DEFAULT_GRANT rounds.
        const val GROWTH_ROUND_CAP = 100
    }

    fun generate(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        @Suppress("UNUSED_PARAMETER") text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
    ): List<ReplyView> {
        val parent = parentId?.let { comments.findById(it) }
        val baseDepth = parent?.let { it.depth + 1 } ?: 0
        // A reply continues its parent branch's depth budget (§4); a top-level reply starts unfuelled.
        val baseBudget = DepthBudget.childBudget(parent?.depthBudget ?: 0)
        // The context differentiator (§5): branch-only = root→parent ancestor path (recursive CTE);
        // whole-thread = the full tree. Branch-only excludes siblings unless the owner opts them in,
        // in which case the reply target's siblings (the other children of its parent) are added.
        val contextComments = if (scope == ScopeMode.BRANCH_ONLY && parentId != null) {
            val path = comments.ancestorPath(parentId)
            if (includeSiblings) {
                (path + comments.childrenOf(parent?.parentId)).distinctBy { it.id }
            } else {
                path
            }
        } else {
            comments.threadComments(threadId)
        }
        // sequential (M1): map preserves order; a failure becomes a FAILED node, not an abort
        return personaIds.map { personaId -> runOne(threadId, parentId, personaId, baseDepth, baseBudget, contextComments) }
    }

    /**
     * Bounded autonomous growth (§4): repeatedly extend every POSTED leaf that still has depth budget
     * by one auto-reply, until the frontier runs dry — the concrete "run-K-turns-then-stop". Because
     * budget is carried per node, a branch grows ~3–4 levels past its last owner comment / `/more`
     * grant and then stalls, and a re-grant on one branch never wakes a quiet sibling. Returns only the
     * nodes created this run. The iteration cap is a runaway backstop: budget never exceeds the grant,
     * so a healthy tree drains in ≤ DEFAULT_GRANT rounds.
     */
    fun autoGrow(threadId: String): List<ReplyView> {
        val pool = personas.findAll()
        if (pool.isEmpty()) return emptyList()
        val created = mutableListOf<ReplyView>()
        var round = 0
        while (round++ < GROWTH_ROUND_CAP) {
            val frontier = comments.growableLeaves(threadId)
            if (frontier.isEmpty()) break
            // Snapshot the thread once per round; freshly-granted /more directives are already in it, so
            // the directive flows into the context handed to the model (§7).
            val context = comments.threadComments(threadId)
            frontier.forEach { leaf ->
                val persona = pool[created.size % pool.size]
                created += runOne(threadId, leaf.id, persona.id, leaf.depth + 1, DepthBudget.childBudget(leaf.depthBudget), context)
            }
        }
        return created
    }

    fun retry(replyId: String): ReplyView {
        val existing = comments.findById(replyId) ?: error("no reply $replyId")
        val persona = personas.find(existing.authorId) ?: error("unknown persona ${existing.authorId}")
        val ctx = ContextAssembler.assemble(persona.systemPrompt, comments.threadComments(existing.threadId))
        val updated = try {
            val resp = llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name, persona.model), timeout), CancellationToken())
            existing.copy(body = resp.text, state = GenerationState.POSTED, failureCategory = null, reason = null, retryAfterSeconds = null)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            existing.copy(body = "", state = o.state, failureCategory = o.failureCategory, reason = o.reason, retryAfterSeconds = o.retryAfterSeconds)
        }
        comments.update(updated)
        return updated.toReplyView()
    }

    private fun runOne(
        threadId: String,
        parentId: String?,
        personaId: String,
        depth: Int,
        budget: Int,
        contextComments: List<Comment>,
    ): ReplyView {
        val persona = personas.find(personaId) ?: error("unknown persona $personaId")
        val ctx = ContextAssembler.assemble(persona.systemPrompt, contextComments)
        val id = UUID.randomUUID().toString()
        val comment = try {
            val resp = llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name, persona.model), timeout), CancellationToken())
            Comment(id, threadId, parentId, personaId, resp.text, GenerationState.POSTED, null, depth, depthBudget = budget)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            Comment(id, threadId, parentId, personaId, "", o.state, o.failureCategory, depth, o.reason, o.retryAfterSeconds, depthBudget = budget)
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
