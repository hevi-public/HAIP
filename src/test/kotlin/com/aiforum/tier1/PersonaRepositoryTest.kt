package com.aiforum.tier1

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.PersonaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: PersonaRepository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins the
 * V4 per-persona `model` column — it must round-trip through insert/find/findAll, and a persona created
 * without one defaults to blank (the aiforum.llm.default-model fallback applies downstream).
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class PersonaRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var personas: PersonaRepository

    @BeforeEach
    fun clean() {
        listOf("vote", "event_log", "comment", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `a pinned model round-trips through insert and find`() {
        personas.insert("vex", "Vex", "systems contrarian", model = "opus")
        assertEquals("opus", personas.find("vex")?.model)
    }

    @Test
    fun `a persona inserted without a model defaults to blank`() {
        personas.insert("sol", "Sol", "index whisperer")
        assertEquals("", personas.find("sol")?.model)
    }

    @Test
    fun `findAll carries each persona's model`() {
        personas.insert("vex", "Vex", "systems contrarian", model = "opus")
        personas.insert("sol", "Sol", "index whisperer")
        assertEquals(mapOf("vex" to "opus", "sol" to ""), personas.findAll().associate { it.id to it.model })
    }

    @Test
    fun `a persona seeded with the pre-V4 columns reads back a blank model`() {
        // TestData.insertPersona omits the model column, exercising the DEFAULT '' the migration sets.
        data.insertPersona(id = "lune", name = "Lune")
        assertEquals("", personas.find("lune")?.model)
    }
}
