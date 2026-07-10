package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.QuoteRepository
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
 * Tier-1: QuoteRepository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins the
 * V18 comment_quote round-trip — insert directed quote edges and read them back grouped by their `src`
 * comment, which is what ReplyTreeAssembler folds into each node's forward "quotes" strip.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class QuoteRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var quotes: QuoteRepository

    // comment_quote FK-cascades with comment, so a stray edge can't FK-block another suite's
    // `DELETE FROM comment` — but clean before AND after anyway so this suite leaves nothing behind.
    @BeforeEach
    @AfterEach
    fun clean() {
        listOf("comment_quote", "comment", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `bySource groups a comment's edges oldest-first, carrying target + snapshot`() {
        val thread = data.insertThread("Scaling SQLite")
        val a = data.insertComment(thread, authorId = "sol", body = "Indexes help a lot")
        val b = data.insertComment(thread, authorId = "saul", body = "Use WAL mode")
        val c = data.insertComment(thread, authorId = "owner", body = "good points")

        // c quotes a, then b — two edges from one src, recorded in that order; a separately quotes b.
        quotes.insert(thread, srcCommentId = c, targetCommentId = a, quotedText = "Indexes help")
        quotes.insert(thread, srcCommentId = c, targetCommentId = b, quotedText = "WAL mode")
        quotes.insert(thread, srcCommentId = a, targetCommentId = b, quotedText = "WAL")

        val bySrc = quotes.bySource(thread)

        assertEquals(setOf(a, c), bySrc.keys, "only quoting comments appear as keys")
        val fromC = bySrc.getValue(c)
        assertEquals(listOf(a, b), fromC.map { it.targetCommentId }, "edges grouped by src, oldest first")
        assertEquals(listOf("Indexes help", "WAL mode"), fromC.map { it.quotedText }, "snapshot preserved verbatim")
        assertEquals(listOf(b), bySrc.getValue(a).map { it.targetCommentId })
    }

    @Test
    fun `byTarget groups incoming edges by the quoted comment, oldest-first`() {
        val thread = data.insertThread("Scaling SQLite")
        val a = data.insertComment(thread, authorId = "sol", body = "Indexes help")
        val b = data.insertComment(thread, authorId = "saul", body = "Use WAL")
        val c = data.insertComment(thread, authorId = "owner", body = "good points")

        // b then c both quote a (the backward direction's many-to-one); c also quotes b.
        quotes.insert(thread, srcCommentId = b, targetCommentId = a, quotedText = "Indexes help")
        quotes.insert(thread, srcCommentId = c, targetCommentId = a, quotedText = "Indexes help")
        quotes.insert(thread, srcCommentId = c, targetCommentId = b, quotedText = "Use WAL")

        val byTgt = quotes.byTarget(thread)

        assertEquals(setOf(a, b), byTgt.keys, "only quoted comments appear as keys")
        assertEquals(listOf(b, c), byTgt.getValue(a).map { it.srcCommentId }, "a's quoters, oldest first")
        assertEquals(listOf(c), byTgt.getValue(b).map { it.srcCommentId })
    }

    @Test
    fun `bySource is scoped to the thread`() {
        val t1 = data.insertThread("One")
        val t2 = data.insertThread("Two")
        val a1 = data.insertComment(t1, authorId = "sol", body = "x")
        val b1 = data.insertComment(t1, authorId = "saul", body = "y")
        val a2 = data.insertComment(t2, authorId = "sol", body = "z")
        val b2 = data.insertComment(t2, authorId = "saul", body = "w")
        quotes.insert(t1, srcCommentId = a1, targetCommentId = b1, quotedText = "y")
        quotes.insert(t2, srcCommentId = a2, targetCommentId = b2, quotedText = "w")

        assertEquals(setOf(a1), quotes.bySource(t1).keys)
        assertEquals(setOf(a2), quotes.bySource(t2).keys)
    }

    @Test
    fun `deleting an endpoint comment cascades its edges`() {
        val thread = data.insertThread("T")
        val a = data.insertComment(thread, authorId = "sol", body = "aaa")
        val b = data.insertComment(thread, authorId = "saul", body = "bbb")
        quotes.insert(thread, srcCommentId = a, targetCommentId = b, quotedText = "bbb")

        // Deleting the TARGET removes the edge via ON DELETE CASCADE — no manual cleanup in the repo.
        jdbc.update("DELETE FROM comment WHERE id = ?", b)

        assertEquals(emptyMap<String, Any>(), quotes.bySource(thread), "the edge is gone with its target")
    }
}
