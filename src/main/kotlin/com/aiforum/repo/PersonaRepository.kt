package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    // `model` pins the LLM this persona generates with (V4); blank carries the aiforum.llm.default-model
    // fallback. Default "" so existing call sites — and the create form, which doesn't yet expose it —
    // keep compiling unchanged.
    data class Persona(val id: String, val name: String, val descriptor: String, val systemPrompt: String, val model: String = "")

    fun find(id: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model FROM persona WHERE id = ?",
            { rs, _ -> mapPersona(rs) },
            id,
        ).firstOrNull()

    fun findAll(): List<Persona> =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model FROM persona ORDER BY name",
        ) { rs, _ -> mapPersona(rs) }

    fun insert(id: String, name: String, descriptor: String, model: String = "") {
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, model) VALUES (?,?,?,?,?,?,?)",
            id, name, name.lowercase(), descriptor, "You are $name.", "— $name", model,
        )
    }

    private fun mapPersona(rs: java.sql.ResultSet) = Persona(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("descriptor") ?: "",
        rs.getString("system_prompt"),
        rs.getString("model") ?: "",
    )
}
