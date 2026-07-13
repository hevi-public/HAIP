package com.aiforum.dto

/**
 * Pastel avatar colours. Each persona owns a stable colour SLOT (Persona.colorIndex, assigned once at
 * creation and stored), so a persona's colour is bound to it for life and adding others never shifts
 * it. Templates emit the resolved hue as the `--mono-h` custom property; app.css builds the pastel
 * `hsl()` at one fixed lightness/saturation.
 */
object Avatar {
    /**
     * 20-colour pastel palette, as hues. The FIRST 10 are spaced ~36° apart so a typical roster is
     * maximally distinguishable; 11–20 fill the midpoints for larger rosters (colours then wrap).
     */
    val PALETTE = listOf(
        // first 10 — evenly spread around the wheel, maximally distinct
        8, 44, 80, 116, 152, 188, 224, 260, 296, 332,
        // 11–20 — the midpoints between the first ten
        26, 62, 98, 134, 170, 206, 242, 278, 314, 350,
    )

    // Non-persona voices get their own reserved hues (deliberately NOT in PALETTE) so they stay
    // distinct from the roster and never change.
    const val OWNER_HUE = 215
    const val SYSTEM_HUE = 320

    /** Hue for a persona's stored colour slot; wraps past the palette for very large rosters. */
    fun hueForIndex(colorIndex: Int): Int = PALETTE[Math.floorMod(colorIndex, PALETTE.size)]

    /** Stable hue for a non-persona author: owner/system reserved, anything else hashed into PALETTE. */
    fun reservedHue(authorId: String): Int = when (authorId) {
        "owner" -> OWNER_HUE
        "system" -> SYSTEM_HUE
        else -> hueForIndex(stableHash(authorId))
    }

    private fun stableHash(s: String): Int {
        var h = 0
        for (ch in s) h = h * 31 + ch.code
        return h
    }
}
