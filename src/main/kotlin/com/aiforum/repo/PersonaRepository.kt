package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    // `model` pins the LLM this persona generates with (V4); blank carries the aiforum.llm.default-model
    // fallback. `slug` is the URL-safe name used in profile links (V5): lower-cased, spaces → hyphens.
    // `colorIndex` (V6) is the persona's stable avatar-colour slot — assigned once at insert as the next
    // free slot, so it's bound to the persona for life and adding/removing others never recolours it.
    data class Persona(val id: String, val name: String, val descriptor: String, val systemPrompt: String, val model: String = "", val slug: String = "", val colorIndex: Int = 0)

    fun find(id: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug, color_index FROM persona WHERE id = ?",
            { rs, _ -> mapPersona(rs) },
            id,
        ).firstOrNull()

    fun findBySlug(slug: String): Persona? =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug, color_index FROM persona WHERE slug = ?",
            { rs, _ -> mapPersona(rs) },
            slug,
        ).firstOrNull()

    fun findAll(): List<Persona> =
        jdbc.query(
            "SELECT id, name, descriptor, system_prompt, model, slug, color_index FROM persona ORDER BY name",
        ) { rs, _ -> mapPersona(rs) }

    fun insert(id: String, name: String, descriptor: String, model: String = "", slug: String = slugFor(name)) {
        // Next free colour slot: MAX+1 is monotonic and never reused, so a persona's colour is stable
        // for life and unaffected by additions or deletions of others.
        val colorIndex = jdbc.queryForObject("SELECT COALESCE(MAX(color_index), -1) + 1 FROM persona", Int::class.java) ?: 0
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, model, slug, color_index) VALUES (?,?,?,?,?,?,?,?,?)",
            id, name, name.lowercase(), descriptor, systemPromptFor(name, descriptor), "— $name", model, slug, colorIndex,
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
        rs.getInt("color_index"),
    )

    companion object {
        fun slugFor(name: String): String =
            name.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")
    }
}
