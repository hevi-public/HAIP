package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

@Repository
class ThreadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    data class Thread(val id: String, val title: String)

    fun insert(id: String, title: String) {
        jdbc.update(
            "INSERT INTO thread(id, title, created_at) VALUES (?,?,?)",
            id, title, clock.instant().toString(),
        )
    }

    fun find(id: String): Thread? =
        jdbc.query(
            "SELECT id, title FROM thread WHERE id = ?",
            { rs, _ -> Thread(rs.getString("id"), rs.getString("title")) },
            id,
        ).firstOrNull()
}
