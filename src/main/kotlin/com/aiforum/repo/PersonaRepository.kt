package com.aiforum.repo

import com.aiforum.persona.Dials
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue

@Repository
class PersonaRepository(private val jdbc: JdbcTemplate) {

    // abilities (JSON array) + dials (JSON object) are stored as text (V10); a small Jackson mapper
    // round-trips them so user-typed ability tags with commas/quotes survive without hand-rolled escaping.
    private val json = jacksonMapperBuilder().build()

    // `model` pins the LLM this persona generates with (V4); blank carries the aiforum.llm.default-model
    // fallback. `slug` is the URL-safe name used in profile links (V5): lower-cased, spaces → hyphens.
    // `colorIndex` (V6) is the persona's stable avatar-colour slot — assigned once at insert as the next
    // free slot, so it's bound to the persona for life and adding/removing others never recolours it.
    // `abilities`/`dials` (V10) are the structured authoring inputs the composer turned into systemPrompt.
    data class Persona(
        val id: String,
        val name: String,
        val descriptor: String,
        val systemPrompt: String,
        val model: String = "",
        val slug: String = "",
        val colorIndex: Int = 0,
        val abilities: List<String> = emptyList(),
        val dials: Map<String, Int> = emptyMap(),
    )

    private val columns = "id, name, descriptor, system_prompt, model, slug, color_index, abilities, dials"

    fun find(id: String): Persona? =
        jdbc.query("SELECT $columns FROM persona WHERE id = ?", { rs, _ -> mapPersona(rs) }, id).firstOrNull()

    fun findBySlug(slug: String): Persona? =
        jdbc.query("SELECT $columns FROM persona WHERE slug = ?", { rs, _ -> mapPersona(rs) }, slug).firstOrNull()

    fun findAll(): List<Persona> =
        jdbc.query("SELECT $columns FROM persona ORDER BY name") { rs, _ -> mapPersona(rs) }

    /**
     * Insert a persona. `systemPrompt` defaults to the deterministic forum framing so seeding (which
     * runs at startup with no LLM available) keeps working unchanged; the admin create path passes the
     * LLM-composed prompt explicitly along with the `abilities`/`dials` it was composed from.
     */
    fun insert(
        id: String,
        name: String,
        descriptor: String,
        model: String = "",
        slug: String = slugFor(name),
        systemPrompt: String = systemPromptFor(name, descriptor),
        abilities: List<String> = emptyList(),
        dials: Map<String, Int> = emptyMap(),
    ) {
        // Next free colour slot: MAX+1 is monotonic and never reused, so a persona's colour is stable
        // for life and unaffected by additions or deletions of others.
        val colorIndex = jdbc.queryForObject("SELECT COALESCE(MAX(color_index), -1) + 1 FROM persona", Int::class.java) ?: 0
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, model, slug, color_index, abilities, dials) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id, name, name.lowercase(), descriptor, systemPrompt, "— $name", model, slug, colorIndex,
            json.writeValueAsString(abilities), json.writeValueAsString(Dials.normalize(dials)),
        )
    }

    /**
     * Re-save an existing persona after an edit. `colorIndex` is deliberately untouched so the avatar
     * stays stable; `slug`/`handle` track the (possibly changed) name. The caller supplies the freshly
     * re-composed `systemPrompt` together with the `abilities`/`dials` it reflects.
     */
    fun update(
        id: String,
        name: String,
        descriptor: String,
        model: String,
        systemPrompt: String,
        abilities: List<String>,
        dials: Map<String, Int>,
    ) {
        jdbc.update(
            "UPDATE persona SET name=?, handle=?, descriptor=?, system_prompt=?, model=?, slug=?, abilities=?, dials=? WHERE id=?",
            name, name.lowercase(), descriptor, systemPrompt, model, slugFor(name),
            json.writeValueAsString(abilities), json.writeValueAsString(Dials.normalize(dials)), id,
        )
    }

    /**
     * Remove a persona row. Nothing references persona(id) — comment authorship is stored as a plain
     * attribution string, not a foreign key (V1 schema) — so past comments by this persona keep their
     * byline and this is a clean single-row delete with no cascade. No-op if the id is unknown.
     */
    fun delete(id: String) {
        jdbc.update("DELETE FROM persona WHERE id = ?", id)
    }

    /**
     * Build the persona's system prompt from the owner-authored descriptor (their CHARACTER) plus the
     * forum framing the model needs to stay in role. Used by seeding (no LLM at startup) and as the
     * default; the admin create/edit path replaces this with an LLM-composed prompt.
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
        abilities = readList(rs.getString("abilities")),
        dials = readMap(rs.getString("dials")),
    )

    private fun readList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else json.readValue(raw)

    private fun readMap(raw: String?): Map<String, Int> =
        if (raw.isNullOrBlank()) emptyMap() else json.readValue(raw)

    companion object {
        fun slugFor(name: String): String =
            name.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")
    }
}
