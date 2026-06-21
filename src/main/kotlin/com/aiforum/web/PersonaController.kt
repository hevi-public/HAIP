package com.aiforum.web

import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

data class PersonaView(val id: String, val name: String, val descriptor: String, val slug: String, val model: String = "", val colorIndex: Int = 0)

@Controller
class PersonaController(private val personas: PersonaRepository) {

    // Profile URLs use the slug (V5) so multi-word names ("Ada Lovelace") work without %20 noise.
    @GetMapping("/personas/{slug}")
    fun profile(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        model.addAttribute("persona", PersonaView(persona.id, persona.name, persona.descriptor, persona.slug, persona.model, persona.colorIndex))
        return "persona"
    }

    @GetMapping("/personas")
    fun list(model: Model): String {
        model.addAttribute("personas", personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) })
        return "personas"
    }

    @PostMapping("/personas")
    fun create(
        @RequestParam name: String,
        @RequestParam descriptor: String,
        @RequestParam(defaultValue = "") model: String,
    ): String {
        personas.insert(name, name, descriptor, model)
        val slug = PersonaRepository.slugFor(name)
        return "redirect:/personas/$slug"
    }
}
