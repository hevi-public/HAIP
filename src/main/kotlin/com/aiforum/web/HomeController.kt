package com.aiforum.web

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** View model for a single row on the front page. */
data class ThreadRow(val id: String, val title: String, val unreadCount: Int)

/**
 * Renders the front page (GET /): empty state when no threads exist; otherwise a list of thread
 * rows with unread reply counts (§2). The left rail's Members box lists the roster.
 */
@Controller
class HomeController(
    private val threads: ThreadRepository,
    private val threadReads: ThreadReadRepository,
    private val personas: PersonaRepository,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        val rows = threads.findAll().map { t ->
            ThreadRow(t.id, t.title, threadReads.unreadCount(t.id))
        }
        model.addAttribute("threads", rows)
        model.addAttribute("personas", personas.findAll().map {
            PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex)
        })
        return "index"
    }
}
