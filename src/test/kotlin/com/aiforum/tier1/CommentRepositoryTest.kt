package com.aiforum.tier1

import com.aiforum.acceptance.support.TestData
import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
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

    @Test
    fun `deleteSubtree removes the node, its whole subtree, and their votes — siblings survive`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B          ← B (and R) must survive a delete of the A subtree
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        val b = data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        val a1 = data.insertComment(thread, authorId = "sol", body = "A1", parentId = a, depth = 2)
        // votes on a doomed node, a doomed descendant, and a survivor
        listOf(a, a1, b).forEach { jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", it) }

        val removed = comments.deleteSubtree(a)

        assertEquals(setOf(a, a1), removed.toSet())
        // A and its descendant A1 are gone; R and B survive
        assertEquals(setOf("R", "B"), comments.threadComments(thread).map { it.body }.toSet())
        // votes for the deleted nodes are gone; B's vote survives (no FK violation deleting deepest-first)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id IN (?, ?)", Int::class.java, a, a1))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id = ?", Int::class.java, b))
    }

    @Test
    fun `deleteSubtree on an unknown id is a no-op`() {
        val thread = data.insertThread("Scaling SQLite")
        data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)

        assertEquals(emptyList<String>(), comments.deleteSubtree("does-not-exist"))
        assertEquals(setOf("R"), comments.threadComments(thread).map { it.body }.toSet())
    }

    @Test
    fun `recentPosted returns the newest POSTED comments across threads, newest first, capped`() {
        // Two threads; insert in a deliberately jumbled time order, then assert the query re-sorts.
        val t1 = data.insertThread("io_uring vs epoll")
        val t2 = data.insertThread("Rust in the kernel")
        // created_at is controlled directly so ordering is deterministic (the test Clock is fixed).
        fun post(thread: String, author: String, body: String, at: String) =
            data.insertComment(thread, authorId = author, body = body).also { id ->
                jdbc.update("UPDATE comment SET created_at = ? WHERE id = ?", at, id)
            }

        post(t1, "vex", "oldest", "2026-06-21T10:00:00Z")
        post(t2, "pike", "middle", "2026-06-21T11:00:00Z")
        val newest = post(t1, "sol", "newest", "2026-06-21T12:00:00Z")

        val recent = comments.recentPosted(limit = 2)
        assertEquals(listOf("newest", "middle"), recent.map { it.body })   // newest first, capped at 2
        assertEquals(newest, recent.first().id)
        assertEquals(t1, recent.first().threadId)
        assertEquals("sol", recent.first().authorId)
    }

    @Test
    fun `insert persists the starred flag and toggleStar flips it both ways`() {
        val thread = data.insertThread("Scaling SQLite")
        fun node(author: String, body: String, starred: Boolean) = Comment(
            id = data.newId(), threadId = thread, parentId = null, authorId = author, body = body,
            state = GenerationState.POSTED, failureCategory = null, depth = 0, starred = starred,
        )
        val pinned = node("sol", "pinned", starred = true).also(comments::insert)
        val loose = node("vex", "loose", starred = false).also(comments::insert)

        // insert round-trips the flag
        assertEquals(true, comments.findById(pinned.id)!!.starred)
        assertEquals(false, comments.findById(loose.id)!!.starred)

        // toggle flips and persists, both directions, returning the new state
        assertEquals(true, comments.toggleStar(loose.id))
        assertEquals(true, comments.findById(loose.id)!!.starred)
        assertEquals(false, comments.toggleStar(loose.id))
        assertEquals(false, comments.findById(loose.id)!!.starred)
    }

    @Test
    fun `update keeps a star set on the node — a generation settle never clears it`() {
        val thread = data.insertThread("Scaling SQLite")
        val draft = Comment(
            id = data.newId(), threadId = thread, parentId = null, authorId = "sol", body = "draft",
            state = GenerationState.DRAFTING, failureCategory = null, depth = 0,
        )
        comments.insert(draft)
        comments.toggleStar(draft.id)

        comments.update(draft.copy(state = GenerationState.POSTED, body = "settled"))

        val after = comments.findById(draft.id)!!
        assertEquals(GenerationState.POSTED, after.state)
        assertEquals("settled", after.body)
        assertEquals(true, after.starred) // update() leaves starred alone
    }

    @Test
    fun `toggleStar on an unknown id is a no-op returning false`() {
        assertEquals(false, comments.toggleStar("does-not-exist"))
    }

    @Test
    fun `recentPosted excludes drafts, failures and cancelled nodes`() {
        val thread = data.insertThread("Scaling SQLite")
        data.insertComment(thread, authorId = "sol", body = "posted", state = "POSTED")
        data.insertComment(thread, authorId = "sol", body = "drafting", state = "DRAFTING")
        data.insertComment(thread, authorId = "sol", body = "failed", state = "FAILED")
        data.insertComment(thread, authorId = "sol", body = "cancelled", state = "CANCELLED")

        assertEquals(listOf("posted"), comments.recentPosted(limit = 10).map { it.body })
    }
}
