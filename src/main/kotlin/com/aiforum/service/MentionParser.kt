package com.aiforum.service

import com.aiforum.repo.PersonaRepository.Persona

/**
 * Pulls @mentions out of an owner's composer message and resolves them to roster persona ids.
 *
 * This is the deterministic core under the composer's "type @ to summon a persona" affordance (§4):
 * the JS autocomplete is sugar, but the mention itself is parsed server-side, so "@vex what about X"
 * summons vex even with JS off and even when the "Anyone" dispatcher would otherwise pick. Pure (no
 * IO) — the whole matching taxonomy is unit-tested in MentionParserTest.
 */
object MentionParser {
    // @ followed by a handle of word chars / hyphens. The negative lookbehind on a word char (and a
    // second @) keeps it from firing inside an email address (foo@bar) or a doubled @@ — the @ must
    // start a fresh token, i.e. sit at a boundary, not mid-word.
    private val MENTION = Regex("""(?<![\w@])@([\w-]+)""")

    /**
     * Resolve every @mention in [text] to a roster persona id — by name or slug, case-insensitive — in
     * first-appearance order, de-duplicated. Unknown handles are dropped. Returns empty when the text
     * carries no recognisable mention, so a caller can cheaply distinguish "no mentions" (fall through
     * to the dispatcher) from "these specific personas".
     */
    fun parse(text: String, roster: List<Persona>): List<String> {
        if (roster.isEmpty()) return emptyList()
        val byHandle = HashMap<String, String>()
        // Later puts win, but name/slug/id all point at the same id, so collisions are harmless.
        roster.forEach { p ->
            byHandle[p.name.lowercase()] = p.id
            if (p.slug.isNotBlank()) byHandle[p.slug.lowercase()] = p.id
            byHandle[p.id.lowercase()] = p.id
        }
        val resolved = LinkedHashSet<String>()
        for (match in MENTION.findAll(text)) {
            byHandle[match.groupValues[1].lowercase()]?.let { resolved.add(it) }
        }
        return resolved.toList()
    }
}
