package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

@Repository
class ThreadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    /** [body] is the opening post (the owner's question); null for title-only quick-creates (V7). */
    data class Thread(val id: String, val title: String, val body: String? = null)

    fun insert(id: String, title: String, body: String? = null) {
        jdbc.update(
            "INSERT INTO thread(id, title, body, created_at) VALUES (?,?,?,?)",
            id, title, body, clock.instant().toString(),
        )
    }

    fun find(id: String): Thread? =
        jdbc.query(
            "SELECT id, title, body FROM thread WHERE id = ?",
            { rs, _ -> Thread(rs.getString("id"), rs.getString("title"), rs.getString("body")) },
            id,
        ).firstOrNull()

    fun findAll(): List<Thread> =
        jdbc.query(
            "SELECT id, title, body FROM thread ORDER BY created_at DESC",
            { rs, _ -> Thread(rs.getString("id"), rs.getString("title"), rs.getString("body")) },
        )
}
