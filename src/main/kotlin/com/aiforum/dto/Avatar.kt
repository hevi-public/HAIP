package com.aiforum.dto

/**
 * Stable pastel colour per author for the monogram (initials box). A hue is derived deterministically
 * from the author id, so each persona — and "owner" / "system" — keeps the same colour everywhere,
 * which makes a branch easier to follow at a glance. Templates emit the hue as the `--mono-h` custom
 * property; app.css builds the pastel `hsl()` (one fixed lightness/saturation) from it.
 */
object Avatar {
    /** Curated, well-spaced pastel hues — distinct, and all legible at the same lightness/saturation. */
    val HUES = listOf(8, 28, 45, 92, 140, 168, 200, 222, 260, 292, 320, 342)

    /** Non-persona authors, placed AFTER the registry so they don't steal a persona's colour. */
    val SPECIALS = listOf("owner", "system")

    /**
     * The hue for [authorId]. Authors are coloured by POSITION in the persona registry [order] (by
     * name), with the special authors appended after it — so the whole cast (team + owner + system)
     * gets DISTINCT colours rather than hash collisions. Anyone still unplaced falls back to a stable
     * hash. Deterministic across runs/JVMs, so a colour never shifts for a given registry.
     */
    fun hue(authorId: String, order: List<String> = emptyList()): Int {
        val placed = order + SPECIALS.filter { it !in order }
        val i = placed.indexOf(authorId)
        return if (i >= 0) HUES[i % HUES.size] else HUES[Math.floorMod(stableHash(authorId), HUES.size)]
    }

    private fun stableHash(s: String): Int {
        var h = 0
        for (ch in s) h = h * 31 + ch.code
        return h
    }
}
