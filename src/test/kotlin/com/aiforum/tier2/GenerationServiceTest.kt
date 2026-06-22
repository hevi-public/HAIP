package com.aiforum.tier2

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReasoningLeak
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
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `a reasoning-leak flag flows from the response through to the persisted comment and view`() {
        // The parsers set LlmResponse.reasoningLeak; here we pin that the service persists it as-is and
        // surfaces it on the view (so the node renders a badge), without touching the POSTED state.
        val leakyLlm = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) =
                LlmResponse("The real reply.", ReasoningLeak.ACTUAL)
        }
        val comments = RecordingComments()
        val service = GenerationService(leakyLlm, comments, personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.POSTED, view.state, "a leak is flagged, not failed")
        assertEquals(ReasoningLeak.ACTUAL, view.reasoningLeak)
        assertEquals(ReasoningLeak.ACTUAL, comments.saved.single().reasoningLeak, "the flag is persisted")
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
    fun `the Anyone dispatcher summons everyone it names — breadth follows the pick, not a toggle`() {
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take", "Paul's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "make these faster?")

        assertEquals(listOf("Sol", "Paul"), replies.map { it.authorId }, "Anyone keeps everyone the dispatcher named")
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

    @Test
    fun `summonAsync routes the dispatcher on the worker, then settles the chosen persona`() {
        // The dispatcher's routing call (first scripted body) AND the persona's reply both run on the
        // worker — summonAsync returns at once without touching the LLM, which is the whole point: the
        // create request never blocks on the model.
        val llm = ScriptedLlm(listOf("Sol", "Sol's take"))
        val comments = RecordingComments()
        val registry = InFlightGenerations()
        val service = GenerationService(llm, comments, roster("Sol", "Paul"), registry)

        service.summonAsync("t1", null, listOf(GenerationService.AUTO_PERSONA), "")

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            comments.saved.none { it.authorId == "Sol" && it.state == GenerationState.POSTED }
        ) {
            Thread.sleep(10)
        }

        assertTrue(llm.requests.any { it.persona.name == "Moderator" }, "the dispatcher routed on the worker")
        val sol = comments.saved.singleOrNull { it.authorId == "Sol" }
        assertEquals("Sol's take", sol?.body, "the persona the dispatcher picked drafted and settled")
        assertFalse(service.isSummoning("t1"), "the summon clears once routing + draft registration finish")
    }
}
