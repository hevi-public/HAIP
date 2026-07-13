package com.aiforum.domain

/**
 * The describe lifecycle of an attachment's caption, deliberately mirroring the comment generation
 * states (see GenerationState): NONE = never described, DESCRIBING = a manual describe is in flight,
 * DESCRIBED = a caption is stored, FAILED = the last describe attempt failed (the owner can retry).
 */
enum class CaptionState { NONE, DESCRIBING, DESCRIBED, FAILED }

/**
 * An image attached to an owner-authored node — the thread opening post ([threadId] set) or a comment
 * ([commentId] set), never both (the V13 CHECK). The bytes live on disk under the images dir at
 * [storagePath] (content-addressed by [sha256]); only metadata is persisted here.
 *
 * [caption] is the vision-model description, produced manually and injected into LLM context as text
 * (the caption-only path — raw bytes never reach generation models). Null until the owner describes it.
 */
data class Attachment(
    val id: String,
    val threadId: String?,
    val commentId: String?,
    val sha256: String,
    val storagePath: String,
    val mimeType: String,
    val byteSize: Long,
    val originalFilename: String?,
    val caption: String? = null,
    val captionModel: String? = null,
    val captionState: CaptionState = CaptionState.NONE,
    val sortOrder: Int = 0,
)
