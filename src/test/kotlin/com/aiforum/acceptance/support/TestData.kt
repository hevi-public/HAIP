package com.aiforum.acceptance.support

import com.aiforum.repo.PersonaRepository
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Seeds preconditions directly into the real test SQLite DB (decision #3) so `Given` steps are
 * deterministic and independent of the create endpoints. `When` steps then drive the HTTP API.
 */
@Component
@Profile("test")
class TestData(private val jdbc: JdbcTemplate, private val clock: Clock) {

    fun newId(): String = UUID.randomUUID().toString()

    fun insertPersona(id: String, name: String, systemPrompt: String = "You are $name.") {
        // Mirror the repo: assign the next free colour slot so seeded personas get distinct avatars.
        val colorIndex = jdbc.queryForObject("SELECT COALESCE(MAX(color_index), -1) + 1 FROM persona", Int::class.java) ?: 0
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, slug, color_index) VALUES (?,?,?,?,?,?,?,?)",
            id, name, name.lowercase(), "$name descriptor", systemPrompt, "— $name", PersonaRepository.slugFor(name), colorIndex,
        )
    }

    fun insertThread(title: String): String {
        val id = newId()
        jdbc.update("INSERT INTO thread(id, title, created_at) VALUES (?,?,?)", id, title, clock.instant().toString())
        return id
    }

    /** Insert a comment node; returns its id. parentId null → top-level under the thread. */
    fun insertComment(
        threadId: String,
        authorId: String,
        body: String,
        parentId: String? = null,
        state: String = "POSTED",
        depth: Int = if (parentId == null) 0 else 1,
        depthBudget: Int = 0,
    ): String {
        val id = newId()
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, depth, depth_budget, created_at)
               VALUES (?,?,?,?,?,?,?,?,?)""",
            id, threadId, parentId, authorId, body, state, depth, depthBudget, clock.instant().toString(),
        )
        return id
    }
}
