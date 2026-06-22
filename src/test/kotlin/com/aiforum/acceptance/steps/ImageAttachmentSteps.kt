package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableImageDescriber
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.repo.AttachmentRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.Base64

/**
 * Drives the image-attachment + manual-describe flow over HTTP, then leans on the existing context-spy
 * steps (ContextScopingSteps) to prove the caption — not the bytes — reaches the model. The vision seam
 * is the scriptable [ScriptableImageDescriber]; nothing here does real vision IO.
 */
class ImageAttachmentSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val describer: ScriptableImageDescriber,
    @Suppress("unused") private val llm: ScriptableLlmClient,
    private val attachments: AttachmentRepository,
) {
    private companion object {
        // A real 1x1 PNG, so the ImageStore's magic-byte sniff accepts it and ImageIO can re-encode it.
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        const val ATTACHMENT = "attachment"
    }

    @When("the owner attaches an image with the note {string}")
    fun attachImageNote(note: String) {
        val resp = http.postMultipart(
            "/threads/${world.threadId}/note",
            fields = mapOf("text" to note),
            files = mapOf("images" to PNG),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastFragment = resp.body
        val id = Regex("data-attachment-id=\"([^\"]+)\"").find(resp.body ?: "")?.groupValues?.get(1)
            ?: error("attaching an image returned no attachment node:\n${resp.body}")
        world.replyIds[ATTACHMENT] = id
        // The owner note the image hangs off — so a scenario can delete it (FK cleanup of the attachment).
        Regex("data-reply-id=\"([^\"]+)\"").find(resp.body ?: "")?.let { world.replyIds["imageNote"] = it.groupValues[1] }
    }

    @When("the owner deletes the image note")
    fun deleteImageNote() {
        val nodeId = world.replyIds["imageNote"] ?: error("no image note captured")
        world.lastStatus = http.post("/replies/$nodeId/delete").statusCode.value()
    }

    @Then("the delete succeeds")
    fun deleteSucceeds() {
        assertTrue(world.lastStatus in 200..299, "deleting an image note should succeed, was ${world.lastStatus}")
    }

    @Then("the attachment is gone")
    fun attachmentGone() {
        val id = world.replyIds[ATTACHMENT] ?: error("no attachment captured")
        assertEquals(null, attachments.find(id), "the attachment row should be deleted with its note")
    }

    @Then("the posted note shows an attachment")
    fun noteShowsAttachment() {
        assertTrue(
            world.lastFragment?.contains("data-attachment-id") == true,
            "expected the posted note fragment to render an attachment",
        )
    }

    @Given("the vision model will caption the image {string}")
    fun visionWillCaption(caption: String) {
        describer.nextCaption = caption
    }

    @Given("the vision model will caption the image with the markdown:")
    fun visionWillCaptionMarkdown(markdown: String) {
        describer.nextCaption = markdown
    }

    @Then("the caption renders as a quote")
    fun captionRendersAsQuote() {
        assertTrue(
            world.lastFragment?.contains("<blockquote class=\"attachment__caption\"") == true,
            "the caption should render as a markdown blockquote:\n${world.lastFragment}",
        )
    }

    @Then("the caption renders a code block containing {string}")
    fun captionRendersCodeBlock(code: String) {
        val html = world.lastFragment ?: error("no fragment captured")
        val open = html.indexOf("<blockquote class=\"attachment__caption\"")
        val close = html.indexOf("</blockquote>", open)
        assertTrue(open >= 0 && close > open, "expected an attachment caption blockquote")
        val inside = html.substring(open, close)
        // A real fenced code block rendered through the engine (highlight.js adds hljs), with the
        // transcribed code inside the quote — not a wall of plain text.
        assertTrue(inside.contains("<pre"), "code should render as a <pre> block inside the quote:\n$inside")
        assertTrue(inside.contains("hljs"), "the code block should be syntax-highlighted (hljs):\n$inside")
        // highlight.js wraps tokens in <span>s, so strip tags before checking the transcribed code is there.
        val text = inside.replace(Regex("<[^>]+>"), "")
        assertTrue(text.contains(code), "the transcribed code \"$code\" should be in the block:\n$inside")
    }

    @When("the owner describes the attachment")
    fun describeAttachment() {
        val id = world.replyIds[ATTACHMENT] ?: error("no attachment captured")
        val resp = http.post("/attachments/$id/describe")
        world.lastStatus = resp.statusCode.value()
        world.lastFragment = resp.body
    }

    @Then("the attachment caption is {string}")
    fun captionIs(expected: String) {
        val id = world.replyIds[ATTACHMENT] ?: error("no attachment captured")
        assertEquals(expected, attachments.find(id)?.caption)
    }

    // The behavioural half of the caption-only firewall: the generation request the model was handed must
    // not contain the image's data URI (or any base64 blob) — only the caption text injected as prose.
    @Then("the model context carries no raw image bytes")
    fun contextHasNoBytes() {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(
            req.context.comments.none { it.body.contains("data:image/") || it.body.contains("base64") },
            "raw image data must never reach a generation model (caption-only)",
        )
    }
}
