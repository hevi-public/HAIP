package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock

@Repository
class ThreadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    // body is the opening post's content (§2, V7) — may be blank for title-only / legacy threads.
    data class Thread(val id: String, val title: String, val body: String)

    fun insert(id: String, title: String, body: String) {
        jdbc.update(
            "INSERT INTO thread(id, title, body, created_at) VALUES (?,?,?,?)",
            id, title, body, clock.instant().toString(),
        )
    }

    fun find(id: String): Thread? =
        jdbc.query("SELECT id, title, body FROM thread WHERE id = ?", ::mapThread, id).firstOrNull()

    fun findAll(): List<Thread> =
        jdbc.query("SELECT id, title, body FROM thread ORDER BY created_at DESC", ::mapThread)

    private fun mapThread(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        Thread(rs.getString("id"), rs.getString("title"), rs.getString("body"))
}
