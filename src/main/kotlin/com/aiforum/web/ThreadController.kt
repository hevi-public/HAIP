package com.aiforum.web

import com.aiforum.dto.GenerationState
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Request body for POST /threads. */
data class CreateThreadRequest(
    val title: String = "",
    val text: String = "",
    val personaIds: List<String> = emptyList(),
)

/**
 * Thread-level endpoints: create a thread and view its page. Generation is triggered separately
 * via GenerationController; the fresh thread shows the "waiting on the room" empty state (§2).
 */
@Controller
class ThreadController(
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
) {

    @PostMapping("/threads")
    fun create(@RequestBody req: CreateThreadRequest, model: Model): String {
        val id = UUID.randomUUID().toString()
        threads.insert(id, req.title)
        return renderThread(id, req.title, model)
    }

    @GetMapping("/threads/{id}")
    fun view(@PathVariable id: String, model: Model): String {
        val thread = threads.find(id) ?: return "redirect:/"
        return renderThread(thread.id, thread.title, model)
    }

    private fun renderThread(id: String, title: String, model: Model): String {
        val replies = comments.threadComments(id).map { it.toReplyView() }
        model.addAttribute("threadId", id)
        model.addAttribute("title", title)
        model.addAttribute("replies", replies)
        model.addAttribute("waitingOnRoom", replies.none { it.state == GenerationState.POSTED })
        model.addAttribute("personas", personas.findAll().map { PersonaView(it.id, it.name, it.descriptor) })
        return "thread"
    }
}
