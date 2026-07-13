package com.aiforum.dto

/**
 * Raw rows returned by [com.aiforum.repo.AdminQueryRepository] for the admin drill-down list pages.
 * Kept thin (DB columns only); the controller derives the snippet / "time ago" / permalink for the view.
 */
data class AdminCommentRow(
    val id: String,
    val threadId: String,
    val threadTitle: String,
    val authorId: String,
    val body: String,
    val state: String,
    val failureCategory: String?,
    val reasoningLeak: String?,
    val votes: Int,
    val createdAt: String,
)

/** One attachment on the /admin/attachments drill-down; owner is the thread or comment it hangs off. */
data class AdminAttachmentRow(
    val id: String,
    val ownerCommentId: String?,
    val ownerThreadId: String?,
    val ownerThreadTitle: String?,
    val mimeType: String,
    val byteSize: Long,
    val captionState: String,
    val originalFilename: String?,
)
