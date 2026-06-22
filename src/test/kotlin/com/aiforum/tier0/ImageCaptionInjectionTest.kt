package com.aiforum.tier0

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState
import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.GenerationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: image captions are folded into context as TEXT at the firewall boundary (caption-only). Pure,
 * no Spring, no IO. A DESCRIBED image contributes its caption; an undescribed one a bare marker; and the
 * raw bytes never appear (there's nowhere for them to go — context is text).
 */
@Tag("tier0")
class ImageCaptionInjectionTest {

    private fun comment(id: String, body: String) =
        Comment(id, "t", null, "owner", body, GenerationState.POSTED, null, 0)

    private fun image(commentId: String, caption: String?, state: CaptionState) =
        Attachment(
            id = "att-$commentId", threadId = null, commentId = commentId, sha256 = "sha", storagePath = "p",
            mimeType = "image/png", byteSize = 1, originalFilename = "x.png", caption = caption, captionState = state,
        )

    @Test
    fun `a described image folds its caption into the owner's message body`() {
        val ctx = ContextAssembler.assemble(
            "sys", listOf(comment("c1", "here's the mockup")),
            attachmentsByComment = mapOf("c1" to listOf(image("c1", "a login screen", CaptionState.DESCRIBED))),
        )
        assertEquals("here's the mockup\n\n[Image: a login screen]", ctx.comments.single().body)
    }

    @Test
    fun `an undescribed image contributes a bare marker so the model knows it exists`() {
        val ctx = ContextAssembler.assemble(
            "sys", listOf(comment("c1", "screenshot below")),
            attachmentsByComment = mapOf("c1" to listOf(image("c1", null, CaptionState.NONE))),
        )
        assertEquals("screenshot below\n\n[Image attached (no description)]", ctx.comments.single().body)
    }

    @Test
    fun `a comment with no attachments is unchanged`() {
        val ctx = ContextAssembler.assemble("sys", listOf(comment("c1", "just text")))
        assertEquals("just text", ctx.comments.single().body)
    }

    @Test
    fun `multiple images each contribute a marker`() {
        val ctx = ContextAssembler.assemble(
            "sys", listOf(comment("c1", "two shots")),
            attachmentsByComment = mapOf(
                "c1" to listOf(
                    image("c1", "first", CaptionState.DESCRIBED),
                    image("c1", null, CaptionState.FAILED),
                ),
            ),
        )
        val body = ctx.comments.single().body
        assertTrue(body.contains("[Image: first]"))
        assertTrue(body.contains("[Image attached (no description)]"))
        // The firewall is unchanged: caption text only, never the bytes / a data URI.
        assertFalse(body.contains("data:image/"))
    }
}
