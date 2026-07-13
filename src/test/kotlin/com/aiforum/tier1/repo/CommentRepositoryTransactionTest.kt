package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.CommentRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Tier-1 atomicity proof for the multi-statement repo writes (T1.2). These methods issue several
 * `jdbc.update`s that must succeed or fail as a unit; [CommentRepository] now annotates them
 * `@Transactional`, so a fault mid-unit must roll the whole thing back, leaving the DB untouched.
 *
 * The forced fault is at a REAL boundary, not a mock of internals (the one-seam rule): a `@Primary`
 * [FailingJdbcTemplate] REPLACES the autoconfigured `JdbcTemplate`, so the genuinely Spring-wired
 * `CommentRepository` runs its real production code against it. It shares the SAME test DataSource the
 * autoconfigured `DataSourceTransactionManager` binds, so the statements it DID execute before throwing
 * are enrolled in the active transaction and get rolled back. It throws on the Nth `update` matching a
 * target SQL — the analogue of the `FailingCommentRepository` IO-boundary fault, one statement deeper.
 *
 * This is the check the wiring note demands: there is no explicit `@EnableTransactionManagement`, so we
 * PROVE Boot's autoconfigured boundary is live by driving a mid-unit failure and asserting nothing partial
 * survived. Remove the `@Transactional` annotations and these tests fail (each DELETE/INSERT auto-commits
 * independently and the partial state sticks) — confirmed locally.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class CommentRepositoryTransactionTest {

    /**
     * A [JdbcTemplate] that delegates to the real test DataSource but throws on the Nth `update` whose SQL
     * contains [targetSql]. It is the genuine IO boundary (same connection/DataSource as the tx manager),
     * so the writes it DID run before throwing are enrolled in the active transaction and get rolled back.
     */
    class FailingJdbcTemplate(dataSource: DataSource) : JdbcTemplate(dataSource) {
        // A sentinel that can never be a substring of any real statement (no control chars), so the seam
        // is inert until an arm* call points it at a real SQL fragment.
        @Volatile var targetSql: String = "__never_match__"
        @Volatile var failOnMatch: Int = Int.MAX_VALUE
        private val seen = AtomicInteger(0)

        fun armFailOnComment(failOnMatch: Int) {
            this.targetSql = "DELETE FROM comment WHERE id"
            this.failOnMatch = failOnMatch
            seen.set(0)
        }

        fun armFailOnRevisionInsert(failOnMatch: Int) {
            this.targetSql = "INSERT INTO comment_revision"
            this.failOnMatch = failOnMatch
            seen.set(0)
        }

        fun disarm() {
            this.targetSql = "__never_match__"
            this.failOnMatch = Int.MAX_VALUE
            seen.set(0)
        }

        override fun update(sql: String, vararg args: Any?): Int {
            if (sql.contains(targetSql) && seen.incrementAndGet() == failOnMatch) {
                throw IllegalStateException("simulated mid-unit write failure (test seam) on: $sql")
            }
            return super.update(sql, *args)
        }
    }

    @TestConfiguration
    class FailingRepoConfig {
        // @Primary so the real Spring-wired CommentRepository (and the @Transactional proxy around it) is
        // constructed against this failing template. Build it from the DataSource directly: depending on
        // the autoconfigured JdbcTemplate would be a cycle (this @Primary bean IS a JdbcTemplate, so the
        // default `jdbcTemplate` is conditionally skipped and can't be injected).
        @Bean
        @Primary
        fun failingJdbcTemplate(dataSource: DataSource) = FailingJdbcTemplate(dataSource)
    }

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var comments: CommentRepository
    @Autowired lateinit var failing: FailingJdbcTemplate

    private fun count(table: String, where: String, arg: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $where", Int::class.java, arg) ?: 0

    // Wipe in FK-safe order: dependents (vote/comment_revision/attachment) before comment, comment before
    // thread. Run BOTH before and AFTER each test: this class COMMITS comment + comment_revision rows (the
    // forced rollback restores the pre-call state, which still includes the seeded comment + its revision),
    // so leaving them behind would trip a sibling tier1 class whose own clean deletes `comment` but not
    // `comment_revision` (PersonaRepositoryTest/ThreadRepositoryTest) — a FK violation. Cleaning up after
    // ourselves keeps the shared test DB pristine regardless of class run order.
    private fun wipe() {
        failing.disarm()
        listOf("vote", "comment_revision", "attachment", "event_log", "comment", "thread", "persona")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    @BeforeEach
    fun before() = wipe()

    @AfterEach
    fun after() = wipe()

    @Test
    fun `deleteSubtree rolls back wholly when the per-id DELETE loop fails mid-unit`() {
        // tree:  R ── A ── A1   (delete the A subtree: A then A1, two comment DELETEs)
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r, depth = 1)
        val a1 = data.insertComment(thread, authorId = "sol", body = "A1", parentId = a, depth = 2)
        listOf(a, a1).forEach { jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", it) }
        comments.addRevision(a, 0, "v0", null)

        // Fail on the SECOND comment DELETE — after the votes/revisions/attachments batch deletes AND the
        // first (deepest) comment delete have already run inside the transaction.
        failing.armFailOnComment(failOnMatch = 2)

        assertThrows(IllegalStateException::class.java) { comments.deleteSubtree(a) }

        // Nothing partial survived: every comment, vote and revision is exactly as before the call.
        assertEquals(3, count("comment", "thread_id = ?", thread), "all three comments must survive the rollback")
        assertEquals(setOf("R", "A", "A1"), comments.threadComments(thread).map { it.body }.toSet())
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM vote", Int::class.java))
        assertEquals(1, count("comment_revision", "comment_id = ?", a), "the rolled-back DELETE must restore the revision")
    }

    @Test
    fun `editBody rolls back wholly when the new-revision INSERT fails mid-unit`() {
        val thread = data.insertThread("Scaling SQLite")
        // A comment with NO revisions yet: the first edit seeds idx 0 then inserts idx 1 (two inserts).
        val id = data.insertComment(thread, authorId = "sol", body = "first take")
        val before = comments.findById(id)!!

        // Fail on the SECOND comment_revision INSERT (the new edit revision) — after idx 0 was seeded.
        failing.armFailOnRevisionInsert(failOnMatch = 2)

        assertThrows(IllegalStateException::class.java) { comments.editBody(id, "corrected") }

        // The seed-idx0 insert and the selectRevision update must NOT survive: zero revisions, body intact.
        assertEquals(0, count("comment_revision", "comment_id = ?", id), "the seeded idx-0 revision must roll back")
        comments.findById(id)!!.let {
            assertEquals("first take", it.body, "the live body must be unchanged")
            assertEquals(before.revisionIndex, it.revisionIndex, "revision_index must be unchanged")
            assertEquals(before.updatedAt, it.updatedAt, "updated_at (the edited marker) must be unchanged")
        }
    }

    @Test
    fun `with the seam disarmed the same multi-write methods commit normally — the proxy is transparent on success`() {
        // Guards against the failing seam silently breaking the happy path (e.g. swallowing a real update).
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r, depth = 1)

        assertEquals(true, comments.editBody(a, "corrected"))
        assertEquals("corrected", comments.findById(a)!!.body)

        assertEquals(setOf(a), comments.deleteSubtree(a).toSet())
        assertEquals(setOf("R"), comments.threadComments(thread).map { it.body }.toSet())
    }
}
