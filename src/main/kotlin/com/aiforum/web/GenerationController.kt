package com.aiforum.web

import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.GenerationService
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Request body for POST /threads/{id}/generate. */
data class GenerateRequest(
    val personaIds: List<String> = emptyList(),
    val text: String = "",
    val scope: String? = null,
    // non-null with a default — works because the Jackson 3 Kotlin module applies Kotlin defaults to
    // omitted fields (without that module this would 400 on "Cannot map null into type boolean").
    val includeSiblings: Boolean = false,
    val triggerMode: String? = null,
    val parentId: String? = null,
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
        model.addAttribute(
            "replies",
            generation.generate(threadId, req.parentId, req.personaIds, req.text, scope, req.includeSiblings),
        )
        // Hand the fragment what its composers need so freshly-rendered nodes can be replied to.
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        return "fragments/replyList"
    }

    private fun personaViews(): List<PersonaView> =
        personas.findAll().map { PersonaView(it.id, it.name, it.descriptor) }

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

    // Render the single re-generated node (not the list wrapper): the browser Retry button outerHTML-
    // swaps the closest <article>, which needs a lone <article> root — replyList would nest a stray
    // <div class="reply-list"> inside the parent. threadId is left unset, so no composer is re-rendered.
    @PostMapping("/replies/{id}/retry")
    fun retry(@PathVariable id: String, model: Model): String {
        model.addAttribute("reply", generation.retry(id))
        return "fragments/replyNode"
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
        return "fragments/replyList"
    }
}
