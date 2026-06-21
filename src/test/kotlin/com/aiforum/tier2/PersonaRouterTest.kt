package com.aiforum.tier2

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.PersonaRepository.Persona
import com.aiforum.service.PersonaRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2: the "Anyone" dispatcher running real routing logic over a faked LlmClient (the single IO
 * seam). Pins that the model's free-text pick is turned into the right personas, and that every
 * failure mode falls back to the whole room so "Anyone" never picks no one.
 */
@Tag("tier2")
class PersonaRouterTest {

    private fun persona(name: String) = Persona(name, name, "$name's specialty", "You are $name.")
    private val roster = listOf(persona("Sol"), persona("Saul"), persona("Paul"), persona("Mira"))

    /** A seam that returns canned text and records whether it was called at all. */
    private class CannedLlm(private val text: String) : LlmClient {
        var calls = 0
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            calls++
            return LlmResponse(text)
        }
    }

    @Test
    fun `routes a prose answer to the named personas, most-relevant first`() {
        val router = PersonaRouter(CannedLlm("I'd let Paul and Sol take this one."))

        val chosen = router.pick(roster, emptyList()).map { it.name }

        assertEquals(listOf("Paul", "Sol"), chosen, "ordered by where each name appears in the reply")
    }

    @Test
    fun `word-boundary matching ignores names buried inside other words`() {
        // "solve"/"Paulson" must NOT match Sol/Paul — only the standalone name Mira should.
        val router = PersonaRouter(CannedLlm("Let's solve this; ask Paulson's neighbour Mira."))

        val chosen = router.pick(roster, emptyList()).map { it.name }

        assertEquals(listOf("Mira"), chosen)
    }

    @Test
    fun `caps the fan-out even when the model names everyone`() {
        val router = PersonaRouter(CannedLlm("Sol, Saul, Paul, Mira — all of them."))

        assertEquals(PersonaRouter.MAX_PICKS, router.pick(roster, emptyList()).size)
    }

    @Test
    fun `falls back to the whole room when nothing parses`() {
        val router = PersonaRouter(CannedLlm("Hmm, hard to say."))

        assertEquals(roster, router.pick(roster, emptyList()), "an unparseable pick must not drop to no one")
    }

    @Test
    fun `falls back to the whole room when the seam fails`() {
        val router = PersonaRouter(object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) =
                throw LlmException.Timeout()
        })

        assertEquals(roster, router.pick(roster, emptyList()))
    }

    @Test
    fun `a lone persona is chosen without spending an LLM call`() {
        val llm = CannedLlm("unused")
        val router = PersonaRouter(llm)

        val only = listOf(persona("Sol"))
        assertEquals(only, router.pick(only, emptyList()))
        assertTrue(llm.calls == 0, "no point asking the model to choose the only candidate")
    }
}
