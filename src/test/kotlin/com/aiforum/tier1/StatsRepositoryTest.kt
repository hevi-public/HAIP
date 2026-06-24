package com.aiforum.tier1

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.StatsRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: the admin-dashboard aggregates against the real test SQLite DB (see the bdd-tiered-testing
 * skill). Seeds a small fixture spanning every countable dimension and pins each field of the
 * [com.aiforum.dto.ForumStats] snapshot.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class StatsRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var stats: StatsRepository

    // Clean before AND after each test: this suite inserts `attachment` rows that the other repo tests'
    // @BeforeEach cleanup predates, so we must leave none behind to FK-block their `DELETE FROM comment`.
    @BeforeEach
    @AfterEach
    fun clean() {
        listOf("comment_revision", "attachment", "vote", "event_log", "comment", "thread", "persona")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `empty forum reports all zeroes`() {
        val s = stats.snapshot()
        assertEquals(0, s.threads)
        assertEquals(0, s.personas)
        assertEquals(0, s.commentsTotal)
        assertEquals(0, s.votes)
        assertEquals(0, s.regeneratedComments)
        assertEquals(0, s.attachments)
        assertEquals(0L, s.attachmentBytes)
        assertEquals(0, s.stateCount("POSTED"))
        assertEquals(0, s.leakCount("ACTUAL"))
        assertEquals(emptyList<Any>(), s.personaActivity)
    }

    @Test
    fun `snapshot aggregates across every dimension`() {
        data.insertPersona(id = "sol", name = "Sol")
        data.insertPersona(id = "saul", name = "Saul")
        val thread = data.insertThread("Scaling SQLite")

        // Comments across states. Sol posts twice, Saul once → leaderboard [Sol:2, Saul:1].
        val solPost = data.insertComment(thread, authorId = "sol", body = "Indexes help", state = "POSTED")
        data.insertComment(thread, authorId = "sol", body = "Use WAL", state = "POSTED")
        data.insertComment(thread, authorId = "saul", body = "Cache it", state = "POSTED")
        data.insertComment(thread, authorId = "owner", body = "drafting…", state = "DRAFTING")
        val failed = data.insertComment(thread, authorId = "owner", body = "boom", state = "FAILED")
        data.insertComment(thread, authorId = "owner", body = "stopped", state = "CANCELLED")
        jdbc.update("UPDATE comment SET failure_category = 'FAILED_RETRY' WHERE id = ?", failed)
        jdbc.update("UPDATE comment SET reasoning_leak = 'ACTUAL' WHERE id = ?", solPost)

        // Two +1 votes on Sol's post.
        jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", solPost)
        jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'guest')", solPost)

        // One regenerated comment: two revision rows under a single comment id.
        jdbc.update(
            "INSERT INTO comment_revision(comment_id, idx, body, created_at) VALUES (?, 0, 'v0', '2026-01-01T00:00:00Z')",
            solPost,
        )
        jdbc.update(
            "INSERT INTO comment_revision(comment_id, idx, body, created_at) VALUES (?, 1, 'v1', '2026-01-01T00:01:00Z')",
            solPost,
        )

        // One described attachment on the thread (CHECK requires exactly one owner).
        jdbc.update(
            """INSERT INTO attachment(id, thread_id, sha256, storage_path, mime_type, byte_size, caption_state, created_at)
               VALUES ('att1', ?, 'abc', 'ab/abc', 'image/png', 2048, 'DESCRIBED', '2026-01-01T00:00:00Z')""",
            thread,
        )

        val s = stats.snapshot()

        assertEquals(1, s.threads)
        assertEquals(2, s.personas)
        assertEquals(6, s.commentsTotal)
        assertEquals(3, s.stateCount("POSTED"))
        assertEquals(1, s.stateCount("DRAFTING"))
        assertEquals(1, s.stateCount("FAILED"))
        assertEquals(1, s.stateCount("CANCELLED"))
        assertEquals(mapOf("FAILED_RETRY" to 1), s.failuresByCategory)
        assertEquals(2, s.votes)
        assertEquals(1, s.regeneratedComments)
        assertEquals(1, s.attachments)
        assertEquals(2048L, s.attachmentBytes)
        assertEquals(mapOf("DESCRIBED" to 1), s.captionsByState)
        assertEquals(1, s.leakCount("ACTUAL"))
        assertEquals(0, s.leakCount("POSSIBLE"))

        assertEquals(listOf("sol", "saul"), s.personaActivity.map { it.authorId })
        assertEquals(listOf("Sol", "Saul"), s.personaActivity.map { it.name })
        assertEquals(listOf(2, 1), s.personaActivity.map { it.posted })
    }
}
