package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    data class Persona(val id: String, val name: String, val systemPrompt: String)

    fun find(id: String): Persona? =
        jdbc.query(
            "SELECT id, name, system_prompt FROM persona WHERE id = ?",
            { rs, _ -> Persona(rs.getString("id"), rs.getString("name"), rs.getString("system_prompt")) },
            id,
        ).firstOrNull()
}
