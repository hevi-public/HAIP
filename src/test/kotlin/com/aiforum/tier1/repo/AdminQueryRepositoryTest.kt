package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.AdminQueryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: the admin drill-down queries against the real test SQLite DB. Pins each filter (state,
 * failure, leak, author, voted, regenerated) and the attachment list, including the thread join used
 * to build permalinks.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class AdminQueryRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var query: AdminQueryRepository

    // Clean before AND after each test: this is the only tier1 suite that inserts `attachment` rows,
    // and the other repo tests' @BeforeEach cleanup predates that table — so we must not leave an
    // attachment behind to FK-block their `DELETE FROM comment` (tests run across classes alphabetically).
    @BeforeEach
    @AfterEach
    fun clean() {
        listOf("comment_revision", "attachment", "vote", "event_log", "comment", "thread", "persona")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `filters comments by state, failure, leak, author, vote and revision`() {
        data.insertPersona(id = "sol", name = "Sol")
        data.insertPersona(id = "saul", name = "Saul")
        val thread = data.insertThread("Scaling SQLite")

        val solVoted = data.insertComment(thread, authorId = "sol", body = "Indexes help", state = "POSTED")
        val solRegen = data.insertComment(thread, authorId = "sol", body = "Use WAL", state = "POSTED")
        data.insertComment(thread, authorId = "saul", body = "Cache it", state = "POSTED")
        val failed = data.insertComment(thread, authorId = "owner", body = "boom", state = "FAILED")
        data.insertComment(thread, authorId = "owner", body = "stopped", state = "CANCELLED")
        jdbc.update("UPDATE comment SET failure_category = 'FAILED_RETRY' WHERE id = ?", failed)
        jdbc.update("UPDATE comment SET reasoning_leak = 'ACTUAL' WHERE id = ?", solRegen)
        jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", solVoted)
        jdbc.update(
            "INSERT INTO comment_revision(comment_id, idx, body, created_at) VALUES (?, 0, 'v0', '2026-01-01T00:00:00Z')",
            solRegen,
        )
        jdbc.update(
            "INSERT INTO comment_revision(comment_id, idx, body, created_at) VALUES (?, 1, 'v1', '2026-01-01T00:01:00Z')",
            solRegen,
        )

        assertEquals(3, query.byState("POSTED").size)
        assertEquals(listOf(failed), query.byState("FAILED").map { it.id })
        assertEquals(listOf(failed), query.byFailure("FAILED_RETRY").map { it.id })
        assertEquals(listOf(solRegen), query.byLeak("ACTUAL").map { it.id })
        assertEquals(2, query.byAuthor("sol").size)
        assertEquals(listOf(solVoted), query.voted().map { it.id })
        assertEquals(listOf(solRegen), query.regenerated().map { it.id })

        // Rows carry the thread title for the in-list link, and the vote count.
        val voted = query.voted().single()
        assertEquals("Scaling SQLite", voted.threadTitle)
        assertEquals(1, voted.votes)
        assertEquals(5, query.allComments().size)
    }

    @Test
    fun `lists attachments, resolving the owning thread and filtering by caption state`() {
        val thread = data.insertThread("Scaling SQLite")
        val comment = data.insertComment(thread, authorId = "sol", body = "see image", state = "POSTED")
        jdbc.update(
            """INSERT INTO attachment(id, comment_id, sha256, storage_path, mime_type, byte_size, caption_state, created_at)
               VALUES ('att1', ?, 'abc', 'ab/abc', 'image/png', 2048, 'DESCRIBED', '2026-01-01T00:00:00Z')""",
            comment,
        )

        val all = query.attachments()
        assertEquals(1, all.size)
        val a = all.single()
        assertEquals(comment, a.ownerCommentId)
        assertEquals(thread, a.ownerThreadId)
        assertEquals("Scaling SQLite", a.ownerThreadTitle)
        assertEquals("DESCRIBED", a.captionState)

        assertEquals(1, query.attachments("DESCRIBED").size)
        assertTrue(query.attachments("NONE").isEmpty())
    }
}
