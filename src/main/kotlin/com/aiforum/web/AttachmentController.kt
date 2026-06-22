package com.aiforum.web

import com.aiforum.dto.AttachmentView
import com.aiforum.images.ImageStore
import com.aiforum.repo.AttachmentRepository
import com.aiforum.service.AttachmentService
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.multipart.MultipartFile
import java.time.Duration

/**
 * Adapt browser multipart parts to the web-type-agnostic service input, dropping empty parts (a file
 * input left blank still submits an empty part). Shared by the create endpoints in this package.
 */
fun List<MultipartFile>.toUploads(): List<AttachmentService.Upload> =
    mapNotNull { f -> if (f.isEmpty) null else AttachmentService.Upload(f.bytes, f.originalFilename) }

/**
 * Serves attachment bytes and drives the manual "Describe" action. Bytes are looked up by attachment id
 * (never a client-supplied path) → the on-disk path comes from the row, so there is no path-traversal
 * surface. The content is content-addressed and immutable, so it's served with a long cache lifetime.
 */
@Controller
class AttachmentController(
    private val attachments: AttachmentRepository,
    private val store: ImageStore,
    private val service: AttachmentService,
) {

    @GetMapping("/attachments/{id}")
    fun serve(@PathVariable id: String): ResponseEntity<Resource> {
        val a = attachments.find(id) ?: return ResponseEntity.notFound().build()
        val bytes = try {
            store.bytes(a.storagePath)
        } catch (_: Exception) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(a.mimeType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(ByteArrayResource(bytes))
    }

    /**
     * Manually (re)generate the caption for an attachment via the vision seam, then re-render just this
     * attachment's gallery cell for an htmx outerHTML swap. The caption is what flows into LLM context.
     */
    @PostMapping("/attachments/{id}/describe")
    fun describe(@PathVariable id: String, model: Model): Any {
        val updated = service.describe(id) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
        model.addAttribute("attachment", AttachmentView.of(updated))
        model.addAttribute("owner", true)
        return "fragments/attachment"
    }
}
