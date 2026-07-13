package com.aiforum.domain.context

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState
import com.aiforum.domain.Comment
import com.aiforum.llm.ContextComment
import com.aiforum.llm.PromptContext

/**
 * Builds the sanitised PromptContext handed to the model. The firewall (§7/§13) lives HERE: only
 * comment bodies/authors flow through — owner `+1` votes are never part of the context (they live in a
 * separate table and are never passed in). The acceptance suite spies on what the LlmClient received
 * to prove no vote signal leaks. Pure Tier-0.
 *
 * Images are caption-only: an owner-attached image contributes TEXT (its vision-model caption, or a bare
 * marker when undescribed) folded into the owner's message body — raw bytes never reach the model, so any
 * generation model works. This is still firewall-safe: it adds image text the owner authored, not a vote.
 */
object ContextAssembler {
    fun assemble(
        personaSystemPrompt: String,
        comments: List<Comment>,
        targetId: String? = null,
        // comment id -> its attachments. Empty for the no-image path (and the Tier-2 constructions that
        // don't wire an AttachmentRepository), so existing behaviour is unchanged.
        attachmentsByComment: Map<String, List<Attachment>> = emptyMap(),
    ): PromptContext =
        PromptContext(
            personaSystemPrompt = personaSystemPrompt,
            comments = comments.map {
                ContextComment(
                    id = it.id,
                    authorId = it.authorId,
                    body = withImageCaptions(it.body, attachmentsByComment[it.id].orEmpty()),
                    parentId = it.parentId,
                    depth = it.depth,
                )
            },
            targetId = targetId,
        )

    /**
     * Fold image captions into a message body. A DESCRIBED image contributes its caption; an undescribed
     * one contributes a bare marker so the model at least knows an image is present (it just can't see it).
     */
    private fun withImageCaptions(body: String, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return body
        val markers = attachments.joinToString("") { att ->
            when (att.captionState) {
                CaptionState.DESCRIBED -> "\n\n[Image: ${att.caption}]"
                else -> "\n\n[Image attached (no description)]"
            }
        }
        return body + markers
    }
}
