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
        @RequestParam allParams: Map<String, String>,
    ): String {
        // The abilities + dials are the structured inputs; the LLM composes the system prompt from them.
        val spec = PersonaSpec(name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        val systemPrompt = composer.compose(spec)
        personas.insert(name, name, descriptor, model, systemPrompt = systemPrompt, abilities = spec.abilities, dials = spec.dials)
        return "redirect:/personas/${PersonaRepository.slugFor(name)}"
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
        @RequestParam allParams: Map<String, String>,
    ): String {
        val existing = personas.findBySlug(slug) ?: return "redirect:/personas"
        // Hand the model the PREVIOUS values + prompt so it adjusts rather than regenerates (continuity).
        val prior = PriorComposition(
            PersonaSpec(existing.name, existing.descriptor, existing.abilities, existing.dials),
            existing.systemPrompt,
        )
        val nextSpec = PersonaSpec(existing.name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        val systemPrompt = composer.compose(nextSpec, prior)
        personas.update(existing.id, existing.name, descriptor, model, systemPrompt, nextSpec.abilities, nextSpec.dials)
        return "redirect:/personas/${existing.slug}"
    }

    // Pull the fixed-schema dials out of the form (each rendered as a `dial_<key>` range input);
    // missing/blank fall back to the neutral default and PersonaRepository normalizes on the way in.
    private fun dialsFrom(params: Map<String, String>): Map<String, Int> =
        Dials.KEYS.associateWith { key -> params["dial_$key"]?.toIntOrNull() ?: Dials.DEFAULT }

    private fun view(p: PersonaRepository.Persona) =
        PersonaView(p.id, p.name, p.descriptor, p.slug, p.model, p.colorIndex, p.abilities, p.dials, p.systemPrompt)
}
