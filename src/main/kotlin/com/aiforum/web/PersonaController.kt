package com.aiforum.web

import com.aiforum.persona.Abilities
import com.aiforum.persona.Dials
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.PromptComposer
import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

data class PersonaView(
    val id: String,
    val name: String,
    val descriptor: String,
    val slug: String,
    val model: String = "",
    val colorIndex: Int = 0,
    val abilities: List<String> = emptyList(),
    val dials: Map<String, Int> = emptyMap(),
    val systemPrompt: String = "",
)

@Controller
class PersonaController(
    private val personas: PersonaRepository,
    private val composer: PromptComposer,
) {

    // Profile URLs use the slug (V5) so multi-word names ("Ada Lovelace") work without %20 noise.
    @GetMapping("/personas/{slug}")
    fun profile(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        model.addAttribute("persona", view(persona))
        return "persona"
    }

    @GetMapping("/personas")
    fun list(model: Model): String {
        model.addAttribute("personas", personas.findAll().map { view(it) })
        model.addAttribute("dialKeys", Dials.KEYS)
        model.addAttribute("dialDefault", Dials.DEFAULT)
        return "personas"
    }

    @PostMapping("/personas")
    fun create(
        @RequestParam name: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") model: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam(defaultValue = "") systemPrompt: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        // The abilities + dials are the structured inputs the prompt is composed from. Save-what-you-see:
        // a prompt the owner already previewed/edited is persisted as-is; we only compose (a paid call)
        // when none was supplied — the one-shot create path.
        val spec = PersonaSpec(name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        val prompt = systemPrompt.ifBlank { composer.compose(spec) }
        personas.insert(name, name, descriptor, model, systemPrompt = prompt, abilities = spec.abilities, dials = spec.dials)
        return "redirect:/personas/${PersonaRepository.slugFor(name)}"
    }

    /** Compose a prompt from the form inputs WITHOUT persisting — backs the "Preview / Regenerate"
     *  button so the owner can see (and then tweak) the prompt before paying to save it. */
    @PostMapping("/personas/compose")
    @ResponseBody
    fun composePreview(
        @RequestParam name: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam allParams: Map<String, String>,
    ): String = composer.compose(PersonaSpec(name, descriptor, Abilities.parse(abilities), dialsFrom(allParams)))

    /**
     * Delete a persona (modelled on ThreadController.delete, §8). Resolved by slug like the other
     * persona routes; the members-list button outerHTML-swaps the member row away with this empty
     * response. No dependents to cascade — comment authorship is a plain string, not an FK to
     * persona(id) — so historical comments keep their byline. No-op if the slug is unknown.
     */
    @PostMapping("/personas/{slug}/delete")
    @ResponseBody
    fun delete(@PathVariable slug: String): String {
        personas.findBySlug(slug)?.let { personas.delete(it.id) }
        return ""
    }

    @GetMapping("/personas/{slug}/edit")
    fun editForm(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        model.addAttribute("persona", view(persona))
        model.addAttribute("dialKeys", Dials.KEYS)
        return "persona_edit"
    }

    @PostMapping("/personas/{slug}/edit")
    fun edit(
        @PathVariable slug: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") model: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam(defaultValue = "") systemPrompt: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        val existing = personas.findBySlug(slug) ?: return "redirect:/personas"
        val nextSpec = PersonaSpec(existing.name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        // Save-what-you-see, with a resync backstop (see plan_docs/persona-prompt-edit-ux.md):
        //  - blank prompt            → compose (the one-shot path)
        //  - prompt == stored, but a composer input changed → STALE, recompose rather than persist
        //    a prompt that no longer matches the dials (protects a JS-off / bypassed submit)
        //  - otherwise (hand-edited / freshly regenerated) → persist verbatim, no LLM call
        val inputsChanged = nextSpec.dials != existing.dials ||
            nextSpec.abilities != existing.abilities ||
            descriptor != existing.descriptor
        val prompt = when {
            systemPrompt.isBlank() -> composer.compose(nextSpec, priorOf(existing))
            systemPrompt == existing.systemPrompt && inputsChanged -> composer.compose(nextSpec, priorOf(existing))
            else -> systemPrompt
        }
        personas.update(existing.id, existing.name, descriptor, model, prompt, nextSpec.abilities, nextSpec.dials)
        return "redirect:/personas/${existing.slug}"
    }

    /** Re-compose from the form inputs against the persona's PREVIOUS values + prompt, without saving. */
    @PostMapping("/personas/{slug}/compose")
    @ResponseBody
    fun composeEditPreview(
        @PathVariable slug: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        val existing = personas.findBySlug(slug) ?: return ""
        val nextSpec = PersonaSpec(existing.name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        return composer.compose(nextSpec, priorOf(existing))
    }

    // Hand the model the PREVIOUS values + prompt so an edit adjusts rather than regenerates (continuity).
    private fun priorOf(existing: PersonaRepository.Persona) =
        PriorComposition(
            PersonaSpec(existing.name, existing.descriptor, existing.abilities, existing.dials),
            existing.systemPrompt,
        )

    // Pull the fixed-schema dials out of the form (each rendered as a `dial_<key>` range input);
    // missing/blank fall back to the neutral default and PersonaRepository normalizes on the way in.
    private fun dialsFrom(params: Map<String, String>): Map<String, Int> =
        Dials.KEYS.associateWith { key -> params["dial_$key"]?.toIntOrNull() ?: Dials.DEFAULT }

    private fun view(p: PersonaRepository.Persona) =
        PersonaView(p.id, p.name, p.descriptor, p.slug, p.model, p.colorIndex, p.abilities, p.dials, p.systemPrompt)
}
