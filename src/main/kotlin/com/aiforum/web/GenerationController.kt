package com.aiforum.web

import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.GenerationService
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/** Request body for POST /threads/{id}/generate. */
data class GenerateRequest(
    val personaIds: List<String> = emptyList(),
    val text: String = "",
    val scope: String? = null,
    // Scope the "Anyone" dispatcher reads when deciding WHO replies (its own selector in the composer):
    // WHOLE_THREAD = the whole topic, BRANCH_ONLY = just the branch being replied to. Independent of
    // [scope] (which scopes what the chosen persona then READS). Null/unset => WHOLE_THREAD.
    val routingScope: String? = null,
    // non-null with a default — works because the Jackson 3 Kotlin module applies Kotlin defaults to
    // omitted fields (without that module this would 400 on "Cannot map null into type boolean").
    val includeSiblings: Boolean = false,
    val triggerMode: String? = null,
    val parentId: String? = null,
    // True when this carries the owner's own message (the composer path): persist `text` as the owner's
    // node before fanning out, so it appears in the tree AND seeds every summoned persona's context. A
    // bare API summon leaves it false — the personas just weigh in on the existing discussion.
    val postAsOwner: Boolean = false,
)

/**
 * Generation endpoints. Returns the rendered reply-node fragment(s) so acceptance steps can assert on
 * the data-* hooks. Real depth-budget autonomy and roomful concurrency are deferred to the team behind
 * this pinned contract.
 */
@Controller
class GenerationController(
    private val generation: GenerationService,
    private val personas: PersonaRepository,
    private val comments: CommentRepository,
    private val branchIndex: BranchIndexBuilder,
    // Regenerate/revision-nav re-render the node WITH its nested replies intact (a persona reply can have
    // children), so they go through the subtree assembler like the edit path — not the leaf renderNode.
    private val replyTree: ReplyTreeAssembler,
) {

    // Two handlers, one body type each: the browser composer posts application/x-www-form-urlencoded
    // (htmx default — bound by model attribute), while the acceptance suite and any API client post
    // JSON (@RequestBody). Both delegate to one [respond] so the behaviour can't drift between them.
    @PostMapping("/threads/{threadId}/generate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun generateJson(@PathVariable threadId: String, @RequestBody req: GenerateRequest, model: Model): String =
        respond(threadId, req, model)

    @PostMapping("/threads/{threadId}/generate", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun generateForm(@PathVariable threadId: String, req: GenerateRequest, model: Model): String =
        respond(threadId, req, model)

    private fun respond(threadId: String, req: GenerateRequest, model: Model): String {
        // Validation BEFORE spending an LLM call (§4): reject empty question / no persona at the
        // controller tier; no node is created and the LlmClient is never touched. The error fragment
        // carries only the system node — no composer under it (threadId/personas left unset).
        validationError(req)?.let {
            model.addAttribute("replies", listOf(it))
            return "fragments/replyList"
        }
        val scope = req.scope?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD
        val routingScope = req.routingScope?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD
        // Async (§4): start drafting and return the DRAFTING node(s) at once. Each node self-polls
        // GET /replies/{id} and carries a Cancel control; it settles to POSTED|FAILED|CANCELLED later.
        model.addAttribute(
            "replies",
            generation.startGeneration(threadId, req.parentId, req.personaIds, req.text, scope, req.includeSiblings, req.postAsOwner, routingScope),
        )
        // Hand the fragment what its composers need so freshly-rendered nodes can be replied to.
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The owner's own message (postAsOwner) posts immediately, so refresh the rail's branch index as
        // an out-of-band swap alongside the appended nodes. (The summoned personas are still DRAFTING, so
        // they enter the index later, when each settles via the poll endpoint.)
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    private fun personaViews(): List<PersonaView> =
        personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }

    private fun validationError(req: GenerateRequest): ReplyView? {
        val reason = when {
            req.text.isBlank() -> "Please add a question."
            req.personaIds.isEmpty() -> "Select at least one persona."
            else -> return null
        }
        return ReplyView(
            id = UUID.randomUUID().toString(),
            authorId = "system",
            body = "",
            state = GenerationState.FAILED,
            failureCategory = FailureCategory.VALIDATION,
            reason = reason,
            retryable = false,
            retryAfterSeconds = null,
            voteCount = 0,
            depth = 0,
        )
    }

    // Re-generate a FAILED/CANCELLED draft, then render the single node via the shared [renderNode]: the
    // browser Retry button outerHTML-swaps the closest <article>, which needs a lone <article> root —
    // replyList would nest a stray <div class="reply-list"> inside the parent. Pass the thread id (like
    // poll/cancel) so a node that retries to POSTED re-renders WITH its inline composer; a re-failed node
    // stays composer-less via the template's POSTED guard and just offers Retry again.
    @PostMapping("/replies/{id}/retry")
    fun retry(@PathVariable id: String, model: Model): String {
        val reply = generation.retry(id)
        return renderNode(model, reply, comments.findById(id)?.threadId)
    }

    /**
     * Regenerate a POSTED persona reply (§7), keeping prior versions: the service appends a content
     * revision and shows it. The browser "Yes, regenerate" button outerHTML-swaps the closest <article>,
     * so we re-render the WHOLE subtree (replyTree.subtree) — the new body lands while the nested replies
     * survive the swap (a bare node would drop them). The revision count rises, so the node now shows the
     * ‹ › switcher.
     */
    @PostMapping("/replies/{id}/regenerate")
    fun regenerate(@PathVariable id: String, model: Model): String {
        generation.regenerate(id)
        return renderSubtree(model, id)
    }

    /**
     * Switch a reply to a stored revision [idx] (0-based) — the ‹ › switcher. Pure DB (no LLM): the
     * selected take's body becomes the live body, then the node re-renders with its subtree intact. A
     * no-op for an out-of-range index (the node re-renders unchanged).
     */
    @PostMapping("/replies/{id}/revision/{idx}")
    fun revision(@PathVariable id: String, @PathVariable idx: Int, model: Model): String {
        comments.selectRevision(id, idx)
        return renderSubtree(model, id)
    }

    /**
     * The owner posts a note — an ordinary visible comment that flows into generation context like any
     * owner comment, but does not summon any persona. Mirrors the thread-scoped URL of /generate so the
     * main composer can route here without knowing the branch root node ID up front.
     */
    @PostMapping("/threads/{threadId}/note", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun noteForm(
        @PathVariable threadId: String,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) parentId: String?,
        model: Model,
    ): String {
        if (text.isNullOrBlank()) {
            model.addAttribute("replies", emptyList<ReplyView>())
            return "fragments/replyList"
        }
        val parentDepth = parentId?.let { comments.findById(it)?.depth } ?: 0
        val node = Comment(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            parentId = parentId,
            authorId = "owner",
            body = text,
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parentDepth + 1,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(node)
        model.addAttribute("replies", listOf(node.toReplyView()))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The note posts immediately — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    /**
     * Drive bounded autonomous growth (§4): the room auto-replies down each branch that still has depth
     * budget, then stalls. Returns the freshly-grown nodes as the reply-list fragment.
     */
    @PostMapping("/threads/{threadId}/auto-grow")
    fun autoGrow(@PathVariable threadId: String, model: Model): String {
        model.addAttribute("replies", generation.autoGrow(threadId))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // Newly-grown nodes are posted — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    /**
     * Poll the create-time room summon (§4). While the dispatcher's routing call is still in flight the
     * thread has no drafts yet, so the thread page shows a "Summoning the room…" poller that hits this
     * every second. Once routing picks who replies and the drafts are registered, this returns them as the
     * reply-list fragment — htmx swaps the poller for the drafts, which then self-poll to settle. If the
     * summon ends with no drafts (routing failed / empty roster), the poller drops itself so htmx stops.
     */
    @GetMapping("/threads/{threadId}/room")
    fun room(@PathVariable threadId: String, model: Model): String {
        val drafts = generation.inFlightViews(threadId)
        if (drafts.isNotEmpty()) {
            model.addAttribute("replies", drafts)
            model.addAttribute("threadId", threadId)
            model.addAttribute("personas", personaViews())
            return "fragments/replyList"
        }
        model.addAttribute("threadId", threadId)
        model.addAttribute("summoning", generation.isSummoning(threadId))
        return "fragments/roomPoller"
    }

    /**
     * Poll a single node (§4). The DRAFTING fragment self-polls every second; once the node settles, the
     * returned fragment drops the poll trigger so htmx stops. DB-first: a persisted row is the source of
     * truth, so we only fall back to the transient in-flight view while no row exists yet (this closes
     * the brief window between the settle write and the worker releasing the cancel latch).
     */
    @GetMapping("/replies/{id}")
    fun poll(@PathVariable id: String, model: Model): String {
        comments.findById(id)?.let { return renderNode(model, it.toReplyView(), it.threadId) }
        generation.inFlightView(id)?.let { return renderNode(model, it, threadId = null) }
        return emptyNode(model)
    }

    /**
     * Cancel an in-flight draft (§4): trip the shared token and wait for the worker to settle the node to
     * CANCELLED, then render the now-persisted row. A no-op (renders the current state) if the node is
     * unknown or already settled.
     */
    @PostMapping("/replies/{id}/cancel")
    fun cancel(@PathVariable id: String, model: Model): String {
        generation.cancel(id)
        val node = comments.findById(id) ?: return emptyNode(model)
        return renderNode(model, node.toReplyView(), node.threadId)
    }

    // Single-node fragment for an htmx outerHTML swap. threadId (+ personas) only for a settled node, so
    // the inline composer renders once it's posted; a drafting node renders without one. A settle can
    // change the posted set (a draft polling to POSTED, a retry succeeding), so carry a fresh branch
    // index as an out-of-band swap too — this is how a persona reply lands in the rail when it settles.
    private fun renderNode(model: Model, reply: ReplyView, threadId: String?): String {
        model.addAttribute("reply", reply)
        if (threadId != null) {
            model.addAttribute("threadId", threadId)
            model.addAttribute("personas", personaViews())
            model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        }
        return "fragments/replyNode"
    }

    // Like [renderNode] but preserves the node's nested replies through the outerHTML swap (regenerate /
    // revision-nav can target a node that has children). Mirrors the edit path: assemble the subtree, then
    // carry a fresh branch index OOB so the rail's snippet follows the now-changed body.
    private fun renderSubtree(model: Model, id: String): String {
        val node = replyTree.subtree(id) ?: return emptyNode(model)
        model.addAttribute("reply", node)
        comments.findById(id)?.threadId?.let { threadId ->
            model.addAttribute("threadId", threadId)
            model.addAttribute("personas", personaViews())
            model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        }
        return "fragments/replyNode"
    }

    private fun emptyNode(model: Model): String {
        model.addAttribute("replies", emptyList<ReplyView>())
        return "fragments/replyList"
    }
}
