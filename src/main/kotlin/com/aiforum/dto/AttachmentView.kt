package com.aiforum.dto

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState

/**
 * View-model for an attachment in the gallery (the frozen template contract). The template emits stable
 * data-* hooks from these fields. [src] is the serve endpoint; [caption] doubles as the img alt text.
 */
data class AttachmentView(
    val id: String,
    val src: String,
    val mimeType: String,
    val originalFilename: String?,
    val caption: String?,
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
            captionState = a.captionState.name.lowercase(),
            described = a.captionState == CaptionState.DESCRIBED,
        )
    }
}
