package com.aiforum.tier0

import com.aiforum.dto.Avatar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure author→hue mapping behind the pastel monogram colours. No IO — just that the hue
 * is stable per author, always a valid palette entry, and spreads across the palette.
 */
@Tag("tier0")
class AvatarTest {

    @Test
    fun `an author always maps to the same hue`() {
        assertEquals(Avatar.hue("Dana"), Avatar.hue("Dana"))
        assertEquals(Avatar.hue("owner"), Avatar.hue("owner"))
    }

    @Test
    fun `every hue is a curated palette entry`() {
        listOf("Sol", "Saul", "Paul", "Mira", "Dana", "owner", "system").forEach {
            assertTrue(Avatar.hue(it) in Avatar.HUES, "hue for \"$it\" must come from the palette")
        }
    }

    @Test
    fun `distinct authors spread across the whole palette`() {
        // Consecutive char codes step through the buckets, so the 26 letters cover every hue.
        val used = ('a'..'z').map { Avatar.hue(it.toString()) }.toSet()
        assertEquals(Avatar.HUES.size, used.size, "every palette hue should be reachable")
    }

    @Test
    fun `personas in registry order get distinct hues`() {
        // The whole point: no two team members share a colour (the hash alone would collide).
        val team = listOf("Sol", "Saul", "Paul", "Mira", "Dana")
        val hues = team.map { Avatar.hue(it, team) }
        assertEquals(team.size, hues.toSet().size, "each persona must get its own hue")
    }

    @Test
    fun `owner and system get their own colours after the team`() {
        val team = listOf("Sol", "Saul", "Paul", "Mira", "Dana")
        val cast = (team + listOf("owner", "system")).map { Avatar.hue(it, team) }
        assertEquals(cast.size, cast.toSet().size, "the whole cast (team + owner + system) must be distinct")
    }

    @Test
    fun `a persona keeps its hue regardless of who else is in the thread`() {
        // Index by position must be stable for the same registry order.
        val team = listOf("Sol", "Saul", "Paul", "Mira", "Dana")
        assertEquals(Avatar.hue("Mira", team), Avatar.hue("Mira", team))
    }
}
