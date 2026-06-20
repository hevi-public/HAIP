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
import com.aiforum.llm.PromptContext
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Orchestrates generation through the single LlmClient seam (Tier 2 running real Tier-0/1 below it,
 * see the bdd-tiered-testing skill). Sequential fan-out for M1: each persona generates in turn, and
 * one persona failing does not abort the others (partial-roomful).
 *
 * The summon path is **async** (§4): [startGeneration] returns DRAFTING nodes immediately and settles
 * them on a worker thread held by [InFlightGenerations], so a later `POST /replies/{id}/cancel` can trip
 * the in-flight token. [generate] is the synchronous variant (used by the Tier-2 test); [autoGrow] and
 * [retry] stay synchronous (M1 cancel targets in-flight summon drafts only).
 */
@Service
class GenerationService(
    private val llm: LlmClient,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    // Default keeps the 3-arg Tier-2 construction compiling; Spring injects the real @Component bean
    // (a single primary constructor means Spring passes all args, so the default is never used in app).
    private val inFlight: InFlightGenerations = InFlightGenerations(),
) {
    private val timeout = Duration.ofSeconds(120)

    private companion object {
        // Runaway backstop for autoGrow; real growth always drains in ≤ DepthBudget.DEFAULT_GRANT rounds.
        const val GROWTH_ROUND_CAP = 100
    }

    /** A resolved unit of work: one persona's reply, with its id minted up front so it is cancellable. */
    private data class GenPlan(
        val id: String,
        val threadId: String,
        val parentId: String?,
        val persona: PersonaRepository.Persona,
        val depth: Int,
        val budget: Int,
        val context: PromptContext,
    )

    /**
     * Async summon/fan-out (§4): register a DRAFTING node + token per persona, hand the room to a single
     * worker that settles each persona IN ORDER (preserving sequential fan-out and the deque-scripted
     * behaviours), and return the DRAFTING views immediately so the browser can render them and offer a
     * Cancel control. Each node settles to exactly one DB row; until then it lives only in [inFlight].
     */
    fun startGeneration(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        @Suppress("UNUSED_PARAMETER") text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
    ): List<ReplyView> {
        val started = planGeneration(threadId, parentId, personaIds, scope, includeSiblings).map { plan ->
            val draft = draftView(plan)
            val token = inFlight.register(plan.id, draft)
            Triple(plan, token, draft)
        }
        inFlight.submit {
            started.forEach { (plan, token, _) ->
                try {
                    settleOne(plan, token)
                } finally {
                    inFlight.markDone(plan.id)
                }
            }
        }
        return started.map { it.third }
    }

    /** Trip the in-flight token for [replyId] and wait (bounded) for the worker to settle it (§4). */
    fun cancel(replyId: String) = inFlight.cancel(replyId)

    /** The transient DRAFTING view while a node is still in flight — the poll endpoint's DB-first fallback. */
    fun inFlightView(replyId: String): ReplyView? = inFlight.view(replyId)

    /**
     * Synchronous summon/fan-out: settle every persona inline and return the settled views. Kept for the
     * Tier-2 service test, which pins the couldn't-save path on the same persist logic [startGeneration]
     * uses.
     */
    fun generate(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        @Suppress("UNUSED_PARAMETER") text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
    ): List<ReplyView> =
        planGeneration(threadId, parentId, personaIds, scope, includeSiblings)
            .map { settleOne(it, CancellationToken()) }

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
                val plan = GenPlan(
                    id = UUID.randomUUID().toString(),
                    threadId = threadId,
                    parentId = leaf.id,
                    persona = persona,
                    depth = leaf.depth + 1,
                    budget = DepthBudget.childBudget(leaf.depthBudget),
                    context = ContextAssembler.assemble(persona.systemPrompt, context),
                )
                created += settleOne(plan, CancellationToken())
            }
        }
        return created
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

    /** Resolve personas and assemble the (shared) context once, minting a cancellable id per persona. */
    private fun planGeneration(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        scope: ScopeMode,
        includeSiblings: Boolean,
    ): List<GenPlan> {
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
        return personaIds.map { personaId ->
            val persona = personas.find(personaId) ?: error("unknown persona $personaId")
            GenPlan(
                id = UUID.randomUUID().toString(),
                threadId = threadId,
                parentId = parentId,
                persona = persona,
                depth = baseDepth,
                budget = baseBudget,
                context = ContextAssembler.assemble(persona.systemPrompt, contextComments),
            )
        }
    }

    /** The transient view shown while a node drafts — never persisted (no DRAFTING DB row). */
    private fun draftView(plan: GenPlan): ReplyView =
        Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, "", GenerationState.DRAFTING, null, plan.depth, depthBudget = plan.budget)
            .toReplyView()

    /** Run one persona's reply against the seam with [token], classify any failure, and persist it. */
    private fun settleOne(plan: GenPlan, token: CancellationToken): ReplyView {
        val comment = try {
            val resp = llm.generate(LlmRequest(plan.context, PersonaRef(plan.persona.id, plan.persona.name), timeout), token)
            Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, resp.text, GenerationState.POSTED, null, plan.depth, depthBudget = plan.budget)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, "", o.state, o.failureCategory, plan.depth, o.reason, o.retryAfterSeconds, depthBudget = plan.budget)
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
