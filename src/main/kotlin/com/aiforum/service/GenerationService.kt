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
import com.aiforum.repo.ThreadRepository
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
    // The "Anyone" dispatcher (defaulted for the same reason; Spring injects the @Component). Shares the
    // single LlmClient seam, so routing is just another call through the same boundary the tests fake.
    private val router: PersonaRouter = PersonaRouter(llm),
    // The opening post lives on the thread (thread.body), not as a comment, so the context-assembly path
    // needs to read it to seed the room. Nullable-defaulted so the 3/4-arg Tier-2 constructions (which
    // don't exercise OP context) keep compiling; Spring injects the real bean.
    private val threads: ThreadRepository? = null,
) {
    private val timeout = Duration.ofSeconds(120)

    private companion object {
        // Runaway backstop for autoGrow; real growth always drains in ≤ DepthBudget.DEFAULT_GRANT rounds.
        const val GROWTH_ROUND_CAP = 100
        // The author id under which the owner's own composer messages are persisted (matches the seeded
        // "owner" nodes the firewall/context scenarios use).
        const val OWNER_AUTHOR = "owner"
        // Sentinel the composer's default "Anyone" option submits instead of a persona id: it hands the
        // pick to the AI dispatcher ([PersonaRouter]) rather than naming who replies. An explicit
        // persona selection never carries it, so the routing call only happens on the "Anyone" path.
        const val AUTO_PERSONA = "auto"
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
        text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
        postAsOwner: Boolean = false,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
    ): List<ReplyView> {
        // The composer authors the owner's message: persist it as the owner's node first, then summon
        // BENEATH it, so the personas reply to it and it flows into their context (§4/§5).
        val owner = ownerComment(threadId, parentId, text, postAsOwner)
        val anchorId = owner?.id ?: parentId
        // Resolve AFTER persisting the owner's message so the dispatcher routes on the new topic too.
        val resolvedIds = resolvePersonas(threadId, anchorId, routingScope, personaIds, text)
        val started = planGeneration(threadId, anchorId, resolvedIds, scope, includeSiblings).map { plan ->
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
        // Return the owner's freshly-posted node with the DRAFTING persona node(s) NESTED inside it, so
        // the htmx swap appends a subtree that mirrors the tree: each reply sits under the owner message
        // it answers, not as a flat sibling. A bare summon (no owner node) returns the drafts flat.
        val drafts = started.map { it.third }
        return owner?.let { listOf(it.toReplyView(children = drafts)) } ?: drafts
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
        text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
        postAsOwner: Boolean = false,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
    ): List<ReplyView> {
        val owner = ownerComment(threadId, parentId, text, postAsOwner)
        val anchorId = owner?.id ?: parentId
        val resolvedIds = resolvePersonas(threadId, anchorId, routingScope, personaIds, text)
        val replies = planGeneration(threadId, anchorId, resolvedIds, scope, includeSiblings)
            .map { settleOne(it, CancellationToken()) }
        return owner?.let { listOf(it.toReplyView(children = replies)) } ?: replies
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
                val plan = GenPlan(
                    id = UUID.randomUUID().toString(),
                    threadId = threadId,
                    parentId = leaf.id,
                    persona = persona,
                    depth = leaf.depth + 1,
                    budget = DepthBudget.childBudget(leaf.depthBudget),
                    context = ContextAssembler.assemble(persona.systemPrompt, withOpeningPost(threadId, context)),
                )
                created += settleOne(plan, CancellationToken())
            }
        }
        return created
    }

    fun retry(replyId: String): ReplyView {
        val existing = comments.findById(replyId) ?: error("no reply $replyId")
        val persona = personas.find(existing.authorId) ?: error("unknown persona ${existing.authorId}")
        val ctx = ContextAssembler.assemble(persona.systemPrompt, withOpeningPost(existing.threadId, comments.threadComments(existing.threadId)))
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

    /**
     * Persist the owner's composed message as their own POSTED node (§4/§5) and return its id, so the
     * summon that follows parents under it. This is what makes the owner's words both APPEAR in the tree
     * and reach every persona's context — without it the room only ever sees a blank transcript and
     * emits a generic opener. The node GRANTS a fresh depth budget so the branch can auto-grow past it
     * (mirrors a seeded owner comment / `/more`). Returns null when there is nothing to author (a bare
     * summon, or an empty message), leaving the summon parented exactly as before.
     */
    private fun ownerComment(threadId: String, parentId: String?, text: String, postAsOwner: Boolean): Comment? {
        if (!postAsOwner || text.isBlank()) return null
        val parent = parentId?.let { comments.findById(it) }
        val owner = Comment(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            parentId = parentId,
            authorId = OWNER_AUTHOR,
            body = text.trim(),
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parent?.let { it.depth + 1 } ?: 0,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(owner)
        return owner
    }

    /**
     * Turn the requested selection into concrete persona ids. A normal selection passes straight through;
     * the composer's default "Anyone" option submits [AUTO_PERSONA], which hands the choice to the AI
     * dispatcher so it picks who weighs in based on the topic. An empty selection is NOT auto — the
     * controller already rejects that as a validation error — so the routing call is confined to the
     * deliberate "Anyone" path and never fires on the explicit-persona scenarios.
     *
     * [routingScope] is the owner's own "looking at" selector (default whole topic): BRANCH_ONLY narrows
     * the dispatcher to the ancestor path of [anchorId] (the branch being replied to) so the pick reflects
     * that sub-discussion, not the whole tree. It is independent of the generation [scope] the chosen
     * persona then reads.
     *
     * On the "Anyone" path the owner can still steer WHO replies without naming them in the dropdown by
     * @mentioning personas in [text] (the composer's "type @ to summon" affordance): an explicit mention
     * is a deliberate summon, so it takes precedence over the dispatcher. Breadth follows who's tagged:
     * a named chip / @mention resolves to exactly that set; the "Anyone" dispatcher picks the room.
     */
    private fun resolvePersonas(
        threadId: String,
        anchorId: String?,
        routingScope: ScopeMode,
        requested: List<String>,
        text: String,
    ): List<String> {
        // An explicit dropdown/chip selection passes straight through (mentions don't override a named
        // pick — naming someone IS the summon); only the deliberate "Anyone" sentinel routes.
        if (requested.none { it == AUTO_PERSONA }) return requested
        val roster = personas.findAll()
        if (roster.isEmpty()) return emptyList()
        // @mentions summon deterministically — they pre-empt the dispatcher when present.
        MentionParser.parse(text, roster).takeIf { it.isNotEmpty() }?.let { return it }
        val context = if (routingScope == ScopeMode.BRANCH_ONLY && anchorId != null) {
            comments.ancestorPath(anchorId)
        } else {
            comments.threadComments(threadId)
        }
        return router.pick(roster, withOpeningPost(threadId, context)).map { it.id }
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
                context = ContextAssembler.assemble(persona.systemPrompt, withOpeningPost(threadId, contextComments)),
            )
        }
    }

    /**
     * The opening post is the topic itself — it lives on the thread (thread.body), rendered as the post
     * node (id == threadId), NOT as a persisted comment. Inject it at the HEAD of every persona's context
     * so the room engages with the question instead of a blank transcript (the "dropped on the way in"
     * bug). Null when the thread has no body (title-only quick-create) or when [threads] isn't wired (the
     * Tier-2 construction). The synthetic node carries the post's canonical id (threadId), depth 0, no
     * parent — exactly how the page models the OP. Owner-authored, like any composer message already in
     * context: the firewall is about VOTES, not the "owner" label.
     */
    private fun openingPost(threadId: String): Comment? =
        threads?.find(threadId)?.body?.takeIf { it.isNotBlank() }?.let {
            Comment(threadId, threadId, null, OWNER_AUTHOR, it, GenerationState.POSTED, null, 0)
        }

    /** Prepend the opening post to [comments] (deduped) so it heads the context handed to the model. */
    private fun withOpeningPost(threadId: String, comments: List<Comment>): List<Comment> =
        openingPost(threadId)?.takeIf { op -> comments.none { it.id == op.id } }
            ?.let { listOf(it) + comments } ?: comments

    /** The transient view shown while a node drafts — never persisted (no DRAFTING DB row). */
    private fun draftView(plan: GenPlan): ReplyView =
        Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, "", GenerationState.DRAFTING, null, plan.depth, depthBudget = plan.budget)
            .toReplyView()

    /** Run one persona's reply against the seam with [token], classify any failure, and persist it. */
    private fun settleOne(plan: GenPlan, token: CancellationToken): ReplyView {
        val comment = try {
            val resp = llm.generate(LlmRequest(plan.context, PersonaRef(plan.persona.id, plan.persona.name, plan.persona.model), timeout), token)
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
