package com.aiforum.tier0

import com.aiforum.dto.Avatar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure colour-slot → hue mapping behind the pastel avatars. No IO — just that the palette
 * is a stable 20-colour map, the first 10 are distinct, a slot always maps to the same hue (so a
 * persona's colour is bound for life), and the owner/system hues stay reserved.
 */
@Tag("tier0")
class AvatarTest {

    @Test
    fun `the palette has 20 colours`() {
        assertEquals(20, Avatar.PALETTE.size)
    }

    @Test
    fun `the first ten colours are distinct`() {
        val firstTen = (0 until 10).map { Avatar.hueForIndex(it) }
        assertEquals(10, firstTen.toSet().size, "the first ten slots must be distinguishable")
    }

    @Test
    fun `a colour slot always maps to the same hue`() {
        assertEquals(Avatar.hueForIndex(3), Avatar.hueForIndex(3))
        assertEquals(Avatar.PALETTE[3], Avatar.hueForIndex(3))
    }

    @Test
    fun `slots wrap around the palette past the end`() {
        assertEquals(Avatar.hueForIndex(0), Avatar.hueForIndex(20))
        assertEquals(Avatar.hueForIndex(1), Avatar.hueForIndex(21))
    }

    @Test
    fun `owner and system use reserved hues outside the persona palette`() {
        assertEquals(Avatar.OWNER_HUE, Avatar.reservedHue("owner"))
        assertEquals(Avatar.SYSTEM_HUE, Avatar.reservedHue("system"))
        assertFalse(Avatar.OWNER_HUE in Avatar.PALETTE, "owner's hue must not collide with a persona slot")
        assertFalse(Avatar.SYSTEM_HUE in Avatar.PALETTE, "system's hue must not collide with a persona slot")
    }

    @Test
    fun `an unknown non-persona author still gets a stable palette hue`() {
        assertEquals(Avatar.reservedHue("ghost"), Avatar.reservedHue("ghost"))
        assertTrue(Avatar.reservedHue("ghost") in Avatar.PALETTE)
    }
}
