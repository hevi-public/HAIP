package com.aiforum.persona

/** The structured authoring inputs the owner supplies for a persona; the composer turns them into a
 *  system prompt. `descriptor` stays as free-form character notes that ride alongside the dials. */
data class PersonaSpec(
    val name: String,
    val descriptor: String = "",
    val abilities: List<String> = emptyList(),
    val dials: Map<String, Int> = emptyMap(),
)

/** What an edit hands back to the composer: the values + prompt it produced last time, so the model
 *  ADJUSTS rather than regenerates and the owner's continuity (and any manual tweaks) survive. */
data class PriorComposition(val spec: PersonaSpec, val prompt: String)

/**
 * Builds the meta-prompt sent to the LLM that COMPOSES a persona's system prompt. Pure (Tier 0): given
 * a spec (+ optional prior composition) it returns the exact text handed to the seam, so the whole
 * translation of dials→instructions is unit-tested without an LLM.
 */
object ComposerPrompts {
    /** Synthetic identity the composition call carries on the shared LlmClient seam, so the spy/router
     *  can tell a prompt-authoring call apart from a normal generation call. */
    const val COMPOSER_ID = "__prompt_composer__"
    const val COMPOSER_NAME = "PromptComposer"

    /** The stable role for the authoring model. */
    val SYSTEM: String = buildString {
        append("You are a prompt author for a collaborative brainstorming forum. Given a persona's ")
        append("name, abilities, and personality dials, write a concise system prompt (2–4 sentences) ")
        append("that makes a language model embody that persona when it replies in the forum. ")
        append("Translate each dial into observable behaviour in prose — never mention the numbers or ")
        append("the word \"dial\". The persona's job is to engage with the substance of the discussion; ")
        append("its personality should colour how it contributes, not become the point — so write a ")
        append("light touch, not a caricature. ")
        // Bake the anti-leak contract into every composed prompt, so a persona carries it itself even
        // where the per-generation steer (PromptRenderer.NO_PREAMBLE) doesn't reach. Belt-and-suspenders
        // against models that narrate their chain-of-thought; see plan_docs/local-model-reasoning-leak.md.
        append("End the prompt you write with a directive that the persona replies with ONLY its ")
        append("finished, in-character message — no preamble, no narration of its role, and no visible ")
        append("reasoning or \"thinking process\" (any reasoning wrapped in <think>…</think> tags). ")
        append("Output only the system prompt itself, with no preamble or quotes.")
    }

    /** The per-persona instruction turn: the spec, and on an edit the previous values + prompt. */
    fun instruction(spec: PersonaSpec, prior: PriorComposition? = null): String = buildString {
        append("Persona name: ${spec.name}\n")
        if (spec.descriptor.isNotBlank()) append("Character notes: ${spec.descriptor}\n")
        val abilities = if (spec.abilities.isEmpty()) "(none given)" else spec.abilities.joinToString(", ")
        append("Abilities: $abilities\n")
        append("Personality dials (0 = low, 10 = high):\n")
        Dials.normalize(spec.dials).forEach { (key, value) -> append("  - ${Dials.describe(key)}: $value\n") }
        if (prior != null) {
            append("\nThis is an EDIT — adjust the existing persona, do not start over.\n")
            append("Previous dials:\n")
            Dials.normalize(prior.spec.dials).forEach { (key, value) -> append("  - ${Dials.describe(key)}: $value\n") }
            append("Previous system prompt:\n${prior.prompt}\n")
            append("Rewrite the previous prompt so it reflects the new values, keeping what still fits.")
        }
    }
}
