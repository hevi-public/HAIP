package com.aiforum.tier1

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.ThreadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: the repository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins
 * findActive — the front-page "Active threads" ranking: newest POSTED comment, else thread creation.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class ThreadRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var threads: ThreadRepository

    @BeforeEach
    fun clean() {
        listOf("vote", "event_log", "comment", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    // created_at is controlled directly so ranking is deterministic (the test Clock is fixed).
    private fun threadAt(title: String, at: String): String =
        data.insertThread(title).also { jdbc.update("UPDATE thread SET created_at = ? WHERE id = ?", at, it) }

    private fun postAt(threadId: String, author: String, body: String, at: String, state: String = "POSTED") =
        data.insertComment(threadId, authorId = author, body = body, state = state)
            .also { jdbc.update("UPDATE comment SET created_at = ? WHERE id = ?", at, it) }

    @Test
    fun `findActive ranks by newest POSTED comment, falling back to thread creation`() {
        // older thread, but it gets the most recent reply → it should rank FIRST
        val older = threadAt("io_uring vs epoll", "2026-06-21T10:00:00Z")
        // newer thread with no replies → ranks by its own creation
        val newer = threadAt("Rust in the kernel", "2026-06-21T11:00:00Z")
        postAt(older, "sol", "measure first", "2026-06-21T12:00:00Z")

        val active = threads.findActive(limit = 10)
        assertEquals(listOf("io_uring vs epoll", "Rust in the kernel"), active.map { it.title })
        assertEquals("2026-06-21T12:00:00Z", active.first().lastActivity)   // the reply, not the thread
        assertEquals("2026-06-21T11:00:00Z", active[1].lastActivity)        // fallback to thread creation
    }

    @Test
    fun `findActive ignores non-POSTED comments when ranking`() {
        val a = threadAt("has a draft", "2026-06-21T10:00:00Z")
        val b = threadAt("quiet but newer", "2026-06-21T11:00:00Z")
        // a DRAFTING reply, newer than everything, must NOT lift thread A above thread B
        postAt(a, "sol", "thinking…", "2026-06-21T23:00:00Z", state = "DRAFTING")

        val active = threads.findActive(limit = 10)
        assertEquals(listOf("quiet but newer", "has a draft"), active.map { it.title })
        assertEquals("2026-06-21T10:00:00Z", active[1].lastActivity)   // fell back past the draft
    }

    @Test
    fun `findActive caps the result at the limit`() {
        threadAt("t1", "2026-06-21T10:00:00Z")
        threadAt("t2", "2026-06-21T11:00:00Z")
        threadAt("t3", "2026-06-21T12:00:00Z")

        val active = threads.findActive(limit = 2)
        assertEquals(listOf("t3", "t2"), active.map { it.title })   // newest two only
    }

    @Test
    fun `updateOp rewrites the title and body and stamps updated_at`() {
        val id = data.insertThread("Old title")
        // a fresh thread is unedited — no marker
        threads.find(id)!!.let {
            assertNull(it.updatedAt)
            assertEquals(false, it.edited)
        }

        assertEquals(true, threads.updateOp(id, "New title", "New opening body"))

        threads.find(id)!!.let {
            assertEquals("New title", it.title)
            assertEquals("New opening body", it.body)
            assertNotNull(it.updatedAt)
            assertEquals(true, it.edited)   // the (edited) marker now shows on the OP
        }
    }

    @Test
    fun `updateOp on an unknown id is a no-op returning false`() {
        assertEquals(false, threads.updateOp("does-not-exist", "t", "b"))
    }
}
