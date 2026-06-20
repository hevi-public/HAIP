package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    // `model` pins the LLM this persona generates with (V4); blank carries the aiforum.llm.default-model
    // fallback. `slug` is the URL-safe name used in profile links (V5): lower-cased, spaces → hyphens.
    data class Persona(val id: String, val name: String, val descriptor: String, val systemPrompt: String, val model: String = "", val slug: String = "")

    fun find(id: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug FROM persona WHERE id = ?",
            { rs, _ -> mapPersona(rs) },
            id,
        ).firstOrNull()

    fun findBySlug(slug: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug FROM persona WHERE slug = ?",
            { rs, _ -> mapPersona(rs) },
            slug,
        ).firstOrNull()

    fun findAll(): List<Persona> =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug FROM persona ORDER BY name",
        ) { rs, _ -> mapPersona(rs) }

    fun insert(id: String, name: String, descriptor: String, model: String = "", slug: String = slugFor(name)) {
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, model, slug) VALUES (?,?,?,?,?,?,?,?)",
            id, name, name.lowercase(), descriptor, systemPromptFor(name, descriptor), "— $name", model, slug,
        )
    }

    /**
     * Build the persona's system prompt from the owner-authored descriptor (their CHARACTER) plus the
     * forum framing the model needs to stay in role. The old "You are $name." dropped the descriptor on
     * the floor and gave the model no world, so it broke the fourth wall ("I'm Sol, not the responder
     * here"). Keeping this in the repository means every persona is persisted already wired for
     * generation — the descriptor is the character, this is the stage directions around it.
     */
    private fun systemPromptFor(name: String, descriptor: String): String = buildString {
        append("You are $name, a participant in a collaborative brainstorming forum where the owner ")
        append("poses questions and the room replies in a threaded discussion.")
        if (descriptor.isNotBlank()) append(" Your character: $descriptor")
        append(" Always stay in character as $name and reply directly to the discussion in your own ")
        append("voice. Do not narrate, do not mention being an AI or a model, and do not comment on ")
        append("the prompt or the framing — just contribute your reply as $name.")
    }

    private fun mapPersona(rs: java.sql.ResultSet) = Persona(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("descriptor") ?: "",
        rs.getString("system_prompt"),
        rs.getString("model") ?: "",
        rs.getString("slug") ?: "",
    )

    companion object {
        fun slugFor(name: String): String =
            name.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")
    }
}
