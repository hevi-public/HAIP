package com.aiforum.dto

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState
import com.aiforum.markdown.MarkdownRenderer

/**
 * View-model for an attachment in the gallery (the frozen template contract). The template emits stable
 * data-* hooks from these fields. [src] is the serve endpoint; [caption] (raw text) doubles as the img
 * alt text, while [captionHtml] is that same caption rendered through the markdown engine for display —
 * so a vision model that transcribes code into a fenced block renders it as a real (highlighted) code
 * block inside the quote, not a wall of text. Same XSS firewall as a comment body (raw HTML escaped).
 */
data class AttachmentView(
    val id: String,
    val src: String,
    val mimeType: String,
    val originalFilename: String?,
    val caption: String?,
    val captionHtml: String?,
    // lowercase of CaptionState for the data-caption-state hook + CSS, e.g. "none" | "described".
    val captionState: String,
    val described: Boolean,
) {
    companion object {
        fun of(a: Attachment) = AttachmentView(
            id = a.id,
            src = "/attachments/${a.id}",
            mimeType = a.mimeType,
            originalFilename = a.originalFilename,
            caption = a.caption,
            captionHtml = a.caption?.let(MarkdownRenderer::render),
            captionState = a.captionState.name.lowercase(),
            described = a.captionState == CaptionState.DESCRIBED,
        )
    }
}
