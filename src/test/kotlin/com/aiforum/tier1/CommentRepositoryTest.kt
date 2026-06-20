package com.aiforum.tier1

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.CommentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: the repository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins the
 * `childrenOf` query that backs the branch+siblings scope — it must return only direct children.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class CommentRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var comments: CommentRepository

    @BeforeEach
    fun clean() {
        listOf("vote", "event_log", "comment", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `childrenOf returns only the direct children of a node`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        data.insertComment(thread, authorId = "sol", body = "A1", parentId = a)

        assertEquals(setOf("A", "B"), comments.childrenOf(r).map { it.body }.toSet())
        assertEquals(setOf("A1"), comments.childrenOf(a).map { it.body }.toSet())
        assertEquals(setOf("R"), comments.childrenOf(null).map { it.body }.toSet())
    }

    @Test
    fun `growableLeaves returns only POSTED childless nodes that still have budget`() {
        val thread = data.insertThread("Scaling SQLite")
        // fuelled root with budget, but it has a child → not a leaf, must be excluded
        val root = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0, depthBudget = 4)
        // a leaf that still has budget → the growth frontier
        data.insertComment(thread, authorId = "sol", body = "fuelled-leaf", parentId = root, depth = 1, depthBudget = 3)
        // an exhausted leaf (budget 0) → excluded
        data.insertComment(thread, authorId = "sol", body = "spent-leaf", parentId = root, depth = 1, depthBudget = 0)
        // a leaf with budget but not POSTED → never grown under
        data.insertComment(thread, authorId = "sol", body = "failed-leaf", parentId = root, depth = 1, state = "FAILED", depthBudget = 3)

        assertEquals(setOf("fuelled-leaf"), comments.growableLeaves(thread).map { it.body }.toSet())
    }

    @Test
    fun `descendantCount counts the whole subtree under a node, excluding itself`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        data.insertComment(thread, authorId = "sol", body = "A1", parentId = a)

        assertEquals(3, comments.descendantCount(r))
        assertEquals(1, comments.descendantCount(a))
        assertEquals(0, comments.descendantCount(comments.childrenOf(r).first { it.body == "B" }.id))
    }
}
