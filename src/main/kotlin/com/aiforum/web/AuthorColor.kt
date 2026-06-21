package com.aiforum.web

import com.aiforum.dto.Avatar

/**
 * Resolves an author id to its avatar hue: a persona uses its stored colour slot (so the colour is
 * bound to the persona), and non-persona voices (owner/system) use Avatar's reserved hues. Used by the
 * reply monogram and the branch-index dot so both agree.
 */
object AuthorColor {
    fun hue(authorId: String, personas: List<PersonaView>): Int =
        personas.find { it.id == authorId }?.let { Avatar.hueForIndex(it.colorIndex) }
            ?: Avatar.reservedHue(authorId)
}
