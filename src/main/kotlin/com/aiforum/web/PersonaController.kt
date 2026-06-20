package com.aiforum.web

import com.aiforum.repo.PersonaRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

data class PersonaView(val id: String, val name: String, val descriptor: String)

@Controller
class PersonaController(private val personas: PersonaRepository) {

    @GetMapping("/personas/{id}")
    fun profile(@PathVariable id: String, model: Model): String {
        val persona = personas.find(id) ?: return "redirect:/personas"
        model.addAttribute("persona", PersonaView(persona.id, persona.name, persona.descriptor))
        return "persona"
    }

    @GetMapping("/personas")
    fun list(model: Model): String {
        model.addAttribute("personas", personas.findAll().map { PersonaView(it.id, it.name, it.descriptor) })
        return "personas"
    }

    @PostMapping("/personas")
    fun create(@RequestParam name: String, @RequestParam descriptor: String): String {
        personas.insert(name, name, descriptor)
        return "redirect:/personas/$name"
    }
}
