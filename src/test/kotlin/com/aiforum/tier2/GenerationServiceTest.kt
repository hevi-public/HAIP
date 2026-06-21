package com.aiforum.tier2

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.GenerationService
import com.aiforum.service.InFlightGenerations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    /** An LlmClient that blocks until its token is tripped, then reports cancellation (mirrors the
     *  acceptance HangUntilCancelled behaviour) — lets us drive the real async cancel path. */
    private val hangingLlm = object : LlmClient {
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            while (!cancellation.isCancelled) Thread.sleep(5)
            throw LlmException.Cancelled()
        }
    }

    /** An in-memory repository that just records writes, so we can assert on what was persisted. */
    private class RecordingComments : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val saved = mutableListOf<Comment>()
        override fun insert(c: Comment) { saved += c }
        override fun findById(id: String): Comment? = saved.lastOrNull { it.id == id }
        override fun threadComments(threadId: String): List<Comment> = emptyList()
        override fun ancestorPath(nodeId: String): List<Comment> = emptyList()
    }

    @Test
    fun `startGeneration drafts immediately and a cancel trips the in-flight token to CANCELLED`() {
        val comments = RecordingComments()
        val registry = InFlightGenerations()
        val service = GenerationService(hangingLlm, comments, personas, registry)

        val draft = service.startGeneration("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()
        assertEquals(GenerationState.DRAFTING, draft.state, "the summon returns a DRAFTING node at once")
        assertEquals(0, comments.saved.size, "a draft is not persisted until it settles")

        service.cancel(draft.id) // trips the shared token and waits for the worker to settle the node

        val settled = comments.findById(draft.id)!!
        assertEquals(GenerationState.CANCELLED, settled.state)
        assertEquals(FailureCategory.CANCELLED, settled.failureCategory)
        assertEquals(1, comments.saved.size, "the cancelled node is persisted exactly once")
        assertNull(service.inFlightView(draft.id), "the in-flight entry is evicted once settled")
    }

    /** A roster of more than one persona so the "Anyone" dispatcher actually runs (it short-circuits a
     *  single-member roster). [find]/[findAll] are all the service needs of the repo here. */
    private fun roster(vararg ids: String) = object : PersonaRepository(JdbcTemplate()) {
        private val all = ids.map { Persona(it, it, "", "You are $it.") }
        override fun find(id: String) = all.firstOrNull { it.id == id }
        override fun findAll() = all
    }

    /** Replays scripted bodies through the single seam and records every request, so a test can assert
     *  both the outputs and HOW MANY calls were made (e.g. that the dispatcher was skipped). */
    private class ScriptedLlm(responses: List<String>) : LlmClient {
        private val deque = ArrayDeque(responses)
        val requests = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            requests += request
            return LlmResponse(deque.removeFirst())
        }
    }

    @Test
    fun `Single caps an Anyone pick to one voice even when the dispatcher names several`() {
        // First call is the dispatcher (names two), second is the one persona that survives the cap.
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "make these faster?", single = true)

        assertEquals(1, replies.size, "Single must collapse a multi-persona route to one reply")
        assertEquals("Sol", replies.single().authorId)
        assertEquals("Sol's take", replies.single().body)
    }

    @Test
    fun `Roomful leaves a multi-persona Anyone pick at full breadth`() {
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take", "Paul's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "make these faster?", single = false)

        assertEquals(listOf("Sol", "Paul"), replies.map { it.authorId }, "Roomful keeps everyone the dispatcher named")
    }

    @Test
    fun `an at-mention on the Anyone path summons that persona and skips the dispatcher`() {
        // Only one scripted body: if the dispatcher were consulted the deque would underflow.
        val llm = ScriptedLlm(listOf("Paul's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "@Paul what do you think?")

        assertEquals(1, llm.requests.size, "an @mention pre-empts the dispatcher — no routing call")
        assertEquals("Paul", replies.single().authorId)
        assertEquals("Paul's take", replies.single().body)
    }
}
