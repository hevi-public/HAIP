package com.aiforum.tier1.repo

import com.aiforum.repo.GitHubPrThreadRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: GitHubPrThreadRepository against the real test SQLite DB (V18 `github_pr_thread`). Pins the
 * PR→thread mapping round-trip + the UNIQUE(repo, pr_number) idempotency guard the "Discuss" button relies
 * on.
 *
 * github_pr_thread FK-references thread, so this test inserts its own thread rows AND cleans both tables in
 * BOTH @BeforeEach and @AfterEach — leaving no rows that could FK-block another tier-1 class's `DELETE FROM
 * thread` (the isolation trap the attachment tables hit before; classes run alphabetically with FKs on).
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class GitHubPrThreadRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var repo: GitHubPrThreadRepository

    @BeforeEach @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM github_pr_thread") // child first (FK → thread)
        jdbc.update("DELETE FROM thread")
    }

    private fun seedThread(id: String) {
        jdbc.update(
            "INSERT INTO thread(id, title, body, created_at) VALUES (?,?,?,?)",
            id, "t", "", "2026-01-01T00:00:00Z",
        )
    }

    @Test
    fun `insert then findByPr round-trips the mapping`() {
        seedThread("T1")
        repo.insert("M1", "o/r", 42, "T1", "deadbeef")

        val found = repo.findByPr("o/r", 42)!!
        assertEquals("M1", found.id)
        assertEquals("o/r", found.repo)
        assertEquals(42, found.prNumber)
        assertEquals("T1", found.threadId)
        assertEquals("deadbeef", found.headSha)
    }

    @Test
    fun `findByPr returns null for an unmapped PR`() {
        assertNull(repo.findByPr("o/r", 999))
    }

    @Test
    fun `the same repo and number cannot be mapped twice`() {
        seedThread("T1")
        seedThread("T2")
        repo.insert("M1", "o/r", 42, "T1", null)
        // SQLite isn't in Spring's error-code map, so a constraint breach surfaces as the generic
        // DataAccessException (UncategorizedSQLException) rather than DataIntegrityViolationException; pin it
        // to the UNIQUE(repo, pr_number) guard via the message so this can't pass on some other SQL error.
        val ex = assertThrows(DataAccessException::class.java) {
            repo.insert("M2", "o/r", 42, "T2", null)
        }
        val messages = generateSequence(ex as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(messages.contains("UNIQUE", ignoreCase = true), "expected a UNIQUE constraint violation, got: $messages")
    }

    @Test
    fun `the same number in a different repo is a distinct mapping`() {
        seedThread("T1")
        seedThread("T2")
        repo.insert("M1", "owner/a", 42, "T1", null)
        repo.insert("M2", "owner/b", 42, "T2", null)
        assertEquals("T1", repo.findByPr("owner/a", 42)?.threadId)
        assertEquals("T2", repo.findByPr("owner/b", 42)?.threadId)
    }

    @Test
    fun `threadIdsByNumbers maps each ingested number and an empty input short-circuits`() {
        seedThread("T1")
        seedThread("T2")
        repo.insert("M1", "o/r", 7, "T1", null)
        repo.insert("M2", "o/r", 9, "T2", null)

        val map = repo.threadIdsByNumbers("o/r", listOf(7, 9, 11))
        assertEquals(mapOf(7 to "T1", 9 to "T2"), map, "only ingested numbers appear; #11 is absent")
        assertEquals(emptyMap<Int, String>(), repo.threadIdsByNumbers("o/r", emptyList()))
    }
}
