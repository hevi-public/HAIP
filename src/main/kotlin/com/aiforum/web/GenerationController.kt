package com.aiforum.web

import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.service.GenerationService
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
class GenerationController(private val generation: GenerationService) {

    @PostMapping("/threads/{threadId}/generate")
    fun generate(@PathVariable threadId: String, @RequestBody req: GenerateRequest, model: Model): String {
        // Validation BEFORE spending an LLM call (§4): reject empty question / no persona at the
        // controller tier; no node is created and the LlmClient is never touched.
        validationError(req)?.let {
            model.addAttribute("replies", listOf(it))
            return "fragments/replyList"
        }
        val scope = req.scope?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD
        model.addAttribute(
            "replies",
            generation.generate(threadId, req.parentId, req.personaIds, req.text, scope, req.includeSiblings),
        )
        return "fragments/replyList"
    }

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

    @PostMapping("/replies/{id}/retry")
    fun retry(@PathVariable id: String, model: Model): String {
        model.addAttribute("replies", listOf(generation.retry(id)))
        return "fragments/replyList"
    }
}
