package com.aiforum.tier0

import com.aiforum.repo.PersonaRepository.Persona
import com.aiforum.service.MentionParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure @mention resolver. This is the deterministic core under the composer's "type @ to
 * summon" affordance — it must work with JS off and pin who replies before the dispatcher is ever
 * consulted, so the whole matching taxonomy (by name, by slug, ordering, dedup, false positives) is
 * unit-tested against fixtures with no IO.
 */
@Tag("tier0")
class MentionParserTest {

    private val roster = listOf(
        Persona(id = "vex", name = "vex", descriptor = "perf", systemPrompt = "", slug = "vex"),
        Persona(id = "Sol", name = "Sol", descriptor = "security", systemPrompt = "", slug = "sol"),
        Persona(id = "lune", name = "lune", descriptor = "types", systemPrompt = "", slug = "lune-the-typer"),
    )

    @Test
    fun `resolves a single mention by name`() {
        assertEquals(listOf("vex"), MentionParser.parse("good point @vex", roster))
    }

    @Test
    fun `resolves a mention by slug when it differs from the name`() {
        assertEquals(listOf("lune"), MentionParser.parse("ask @lune-the-typer about this", roster))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("Sol"), MentionParser.parse("what does @SOL think?", roster))
    }

    @Test
    fun `returns every mention in first-appearance order, de-duplicated`() {
        assertEquals(
            listOf("Sol", "vex"),
            MentionParser.parse("@sol and @vex — also @sol again", roster),
        )
    }

    @Test
    fun `ignores handles that match no roster member`() {
        assertEquals(emptyList<String>(), MentionParser.parse("@nobody here", roster))
    }

    @Test
    fun `text with no mention resolves to nothing`() {
        assertEquals(emptyList<String>(), MentionParser.parse("how do we scale this?", roster))
    }

    @Test
    fun `an email address is not a mention`() {
        // The @ is preceded by a word char, so foo@vex.com must not summon vex.
        assertEquals(emptyList<String>(), MentionParser.parse("mail me at foo@vex.com", roster))
    }

    @Test
    fun `trailing punctuation does not break the handle`() {
        assertEquals(listOf("vex"), MentionParser.parse("hey @vex! thoughts?", roster))
    }

    @Test
    fun `an empty roster resolves to nothing`() {
        assertEquals(emptyList<String>(), MentionParser.parse("@vex", emptyList()))
    }
}
