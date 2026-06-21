package com.aiforum.domain.context

import com.aiforum.domain.Comment
import com.aiforum.llm.ContextComment
import com.aiforum.llm.PromptContext

/**
 * Builds the sanitised PromptContext handed to the model. The firewall (§7/§13) lives HERE: only
 * comment bodies/authors flow through — owner `+1` votes are never part of the context (they live in a
 * separate table and are never passed in). The acceptance suite spies on what the LlmClient received
 * to prove no vote signal leaks. Pure Tier-0.
 */
object ContextAssembler {
    fun assemble(personaSystemPrompt: String, comments: List<Comment>, targetId: String? = null): PromptContext =
        PromptContext(
            personaSystemPrompt = personaSystemPrompt,
            comments = comments.map {
                ContextComment(
                    id = it.id,
                    authorId = it.authorId,
                    body = it.body,
                    parentId = it.parentId,
                    depth = it.depth,
                )
            },
            targetId = targetId,
        )
}
