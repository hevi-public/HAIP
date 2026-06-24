package com.aiforum.tier0

import com.aiforum.repo.PersonaRepository.Persona
import com.aiforum.service.PersonaRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure trait-routing helpers the dispatcher uses (no LLM, no IO). [PersonaRouter.rosterLine]
 * folds the structured abilities/dials into the prompt; [PersonaRouter.diversify] re-ranks a capped
 * fan-out to span the agreeableness axis. See plan_docs/persona-traits-routing.md.
 */
@Tag("tier0")
class PersonaRouterTraitsTest {

    private fun persona(
        name: String,
        descriptor: String = "",
        abilities: List<String> = emptyList(),
        dials: Map<String, Int> = emptyMap(),
    ) = Persona(id = name, name = name, descriptor = descriptor, systemPrompt = "", abilities = abilities, dials = dials)

    // --- rosterLine: the enriched roster line the model now sees ---

    @Test
    fun `a roster line carries descriptor, abilities and off-centre dials`() {
        val line = PersonaRouter.rosterLine(
            persona(
                "Sol",
                descriptor = "index whisperer",
                abilities = listOf("kotlin", "backend"),
                dials = mapOf("agreeableness" to 2, "rigor" to 9),
            ),
        )

        assertEquals("- Sol: index whisperer; skills: kotlin, backend; style: contrarian, evidence-led", line)
    }

    @Test
    fun `a trait-less persona renders just its name and descriptor`() {
        assertEquals("- Mira: product lead", PersonaRouter.rosterLine(persona("Mira", descriptor = "product lead")))
        assertEquals("- Bare", PersonaRouter.rosterLine(persona("Bare")))
    }

    @Test
    fun `mid dials carry no signal and are omitted`() {
        // 5 is the default/centre — only notably high/low axes become adjectives.
        val words = PersonaRouter.traitWords(mapOf("agreeableness" to 5, "verbosity" to 8, "warmth" to 1))
        assertEquals(listOf("expansive", "blunt"), words)
    }

    // --- diversify: a capped fan-out spans the agreeableness axis ---

    @Test
    fun `diversify leaves a set already within the cap untouched`() {
        val set = listOf(persona("A"), persona("B"))
        assertEquals(set, PersonaRouter.diversify(set, max = 3))
    }

    @Test
    fun `diversify keeps the top pick and spans contrarian to agreeable, dropping a redundant voice`() {
        val lead = persona("Lead", dials = mapOf("agreeableness" to 5)) // model's #1, neutral
        val echo = persona("Echo", dials = mapOf("agreeableness" to 5)) // redundant neutral
        val builder = persona("Bui", dials = mapOf("agreeableness" to 9)) // agreeable
        val vex = persona("Vex", dials = mapOf("agreeableness" to 1)) // contrarian

        val picked = PersonaRouter.diversify(listOf(lead, echo, builder, vex), max = 3).map { it.name }

        assertEquals("Lead", picked.first(), "the model's most-relevant pick always leads")
        assertTrue(picked.containsAll(listOf("Bui", "Vex")), "the spread keeps both poles of agreeableness")
        assertFalse(picked.contains("Echo"), "the redundant neutral voice is dropped for the span")
    }

    @Test
    fun `diversify with identical dials degrades to taking the first few in order`() {
        // No dial signal => every distance is 0 => ties preserve the model's relevance order.
        val roster = listOf(persona("A"), persona("B"), persona("C"), persona("D"))
        assertEquals(listOf("A", "B", "C"), PersonaRouter.diversify(roster, max = 3).map { it.name })
    }
}
