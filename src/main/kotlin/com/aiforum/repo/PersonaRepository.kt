package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    data class Persona(val id: String, val name: String, val descriptor: String, val systemPrompt: String)

    fun find(id: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt FROM persona WHERE id = ?",
            { rs, _ -> Persona(rs.getString("id"), rs.getString("name"), rs.getString("descriptor") ?: "", rs.getString("system_prompt")) },
            id,
        ).firstOrNull()

    fun findAll(): List<Persona> =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt FROM persona ORDER BY name",
        ) { rs, _ -> Persona(rs.getString("id"), rs.getString("name"), rs.getString("descriptor") ?: "", rs.getString("system_prompt")) }

    fun insert(id: String, name: String, descriptor: String) {
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature) VALUES (?,?,?,?,?,?)",
            id, name, name.lowercase(), descriptor, "You are $name.", "— $name",
        )
    }
}
