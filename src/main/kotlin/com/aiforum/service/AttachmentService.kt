package com.aiforum.service

import com.aiforum.domain.Attachment
import com.aiforum.domain.CaptionState
import com.aiforum.images.DescribeRequest
import com.aiforum.images.ImageDescriber
import com.aiforum.images.ImageStore
import com.aiforum.repo.AttachmentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Orchestrates image attachments: store-and-link on upload, and the MANUAL describe lifecycle. Images
 * only ever hang off owner-authored nodes (the OP or a comment) — personas emit text — so the firewall
 * is preserved: a caption is injected into context as part of the owner's message, never a new signal.
 */
@Service
class AttachmentService(
    private val store: ImageStore,
    private val attachments: AttachmentRepository,
    private val describer: ImageDescriber,
    // Stored alongside a caption so we can see which model produced it; blank under test (the fake).
    @Value("\${aiforum.images.vision.model:}") private val visionModel: String,
) {
    private val log = LoggerFactory.getLogger(AttachmentService::class.java)

    /** An uploaded image, decoupled from Spring's MultipartFile so the service stays web-type-agnostic. */
    data class Upload(val bytes: ByteArray, val originalFilename: String?)

    fun attachToThread(threadId: String, uploads: List<Upload>): List<Attachment> =
        store(uploads) { stored, idx, name -> row(threadId = threadId, commentId = null, stored, idx, name) }

    fun attachToComment(commentId: String, uploads: List<Upload>): List<Attachment> =
        store(uploads) { stored, idx, name -> row(threadId = null, commentId = commentId, stored, idx, name) }

    /**
     * Manually (re)generate a caption for [id] via the vision seam. Lifecycle mirrors generation:
     * DESCRIBING while the call is in flight, then DESCRIBED with the caption, or FAILED (vision disabled,
     * model down, or empty output) — the owner can retry from a FAILED state. Synchronous: it's a
     * deliberate single owner action, so the htmx request waits on the model. Returns the refreshed row,
     * or null for an unknown id.
     */
    fun describe(id: String): Attachment? {
        val a = attachments.find(id) ?: return null
        attachments.setState(id, CaptionState.DESCRIBING)
        return try {
            val caption = describer.describe(DescribeRequest(store.bytes(a.storagePath), a.mimeType))
            attachments.updateCaption(id, caption, visionModel, CaptionState.DESCRIBED)
            attachments.find(id)
        } catch (e: Throwable) {
            log.warn("describe failed for attachment {}: {}", id, e.message)
            attachments.setState(id, CaptionState.FAILED)
            attachments.find(id)
        }
    }

    // --- internals --------------------------------------------------------------------------------

    private fun store(
        uploads: List<Upload>,
        link: (ImageStore.Stored, Int, String?) -> Attachment,
    ): List<Attachment> =
        uploads.mapIndexedNotNull { idx, up ->
            // Skip empty parts (a multipart form with no file selected sends an empty part) and bounce
            // a rejected upload (not an image / too big) without aborting the others.
            if (up.bytes.isEmpty()) return@mapIndexedNotNull null
            val stored = try {
                store.store(up.bytes)
            } catch (e: ImageStore.RejectedException) {
                log.info("rejected upload {}: {}", up.originalFilename, e.message)
                return@mapIndexedNotNull null
            }
            link(stored, idx, up.originalFilename).also(attachments::insert)
        }

    private fun row(
        threadId: String?,
        commentId: String?,
        stored: ImageStore.Stored,
        sortOrder: Int,
        originalFilename: String?,
    ) = Attachment(
        id = UUID.randomUUID().toString(),
        threadId = threadId,
        commentId = commentId,
        sha256 = stored.sha256,
        storagePath = stored.storagePath,
        mimeType = stored.mimeType,
        byteSize = stored.byteSize,
        originalFilename = originalFilename,
        sortOrder = sortOrder,
    )
}
