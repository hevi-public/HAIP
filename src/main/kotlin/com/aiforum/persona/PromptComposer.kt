package com.aiforum.persona

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.springframework.stereotype.Service
import java.time.Duration

/** Turns a persona's structured authoring inputs into its system prompt. */
interface PromptComposer {
    /** Compose a fresh system prompt for [spec]; on an edit pass the [prior] composition so the model
     *  adjusts the existing prompt instead of regenerating it from scratch. */
    fun compose(spec: PersonaSpec, prior: PriorComposition? = null): String
}

/**
 * The production composer: it rides the SAME single [LlmClient] seam as generation (see the
 * bdd-tiered-testing skill — one mock point), tagging the call with the synthetic [ComposerPrompts]
 * persona so the spy/router can tell a prompt-authoring call apart from a normal reply. The dial→prose
 * translation lives entirely in [ComposerPrompts] (Tier 0); this class only does the IO round-trip.
 */
@Service
class LlmPromptComposer(private val llm: LlmClient) : PromptComposer {

    override fun compose(spec: PersonaSpec, prior: PriorComposition?): String {
        val request = LlmRequest(
            context = PromptContext(
                personaSystemPrompt = ComposerPrompts.SYSTEM,
                comments = listOf(
                    ContextComment(
                        id = "spec",
                        authorId = "owner",
                        body = ComposerPrompts.instruction(spec, prior),
                        parentId = null,
                        depth = 0,
                    ),
                ),
            ),
            persona = PersonaRef(ComposerPrompts.COMPOSER_ID, ComposerPrompts.COMPOSER_NAME),
            timeout = COMPOSE_TIMEOUT,
        )
        return llm.generate(request, CancellationToken()).text.trim()
    }

    private companion object {
        // Authoring is synchronous in the create/edit request, so keep it bounded but generous.
        val COMPOSE_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
