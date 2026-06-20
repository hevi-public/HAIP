package com.aiforum.tier2

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.GenerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * Tier-2: the service runs real Tier-0 logic over fakes at the single IO seam (see the
 * bdd-tiered-testing skill). Here we pin the couldn't-save path (UX state E): a write failure must
 * keep the drafted body rather than lose it.
 */
@Tag("tier2")
class GenerationServiceTest {

    private val okLlm = object : LlmClient {
        override fun generate(request: LlmRequest, cancellation: CancellationToken) =
            LlmResponse("Indexes help here")
    }

    private val personas = object : PersonaRepository(JdbcTemplate()) {
        override fun find(id: String) = Persona(id, id, "", "You are $id.")
    }

    /** A repository whose first write throws (a one-shot transient blip), the rest delegate to memory. */
    private class FailingOnceComments : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val saved = mutableListOf<Comment>()
        var failNext = true
        override fun insert(c: Comment) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("simulated write failure")
            }
            saved += c
        }
        override fun threadComments(threadId: String): List<Comment> = emptyList()
        override fun ancestorPath(nodeId: String): List<Comment> = emptyList()
    }

    @Test
    fun `a save failure keeps the drafted body and surfaces COULDNT_SAVE`() {
        val comments = FailingOnceComments()
        val service = GenerationService(okLlm, comments, personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.FAILED, view.state)
        assertEquals(FailureCategory.COULDNT_SAVE, view.failureCategory)
        assertEquals("Indexes help here", view.body, "the drafted text must survive the save failure")
        assertTrue(view.retryable)
        assertEquals(1, comments.saved.size, "the failure marker is persisted so retry has a real row")
    }
}
