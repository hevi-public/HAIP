package com.aiforum.tier0

import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.GenerationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Tier-0: the firewall lives here (§7/§13). Pure, no Spring, no mocks. */
@Tag("tier0")
class ContextAssemblerTest {

    private fun comment(author: String, body: String) =
        Comment("id-$body", "t", null, author, body, GenerationState.POSTED, null, 0)

    @Test
    fun `context carries comment bodies and authors`() {
        val ctx = ContextAssembler.assemble("you are sol", listOf(comment("sol", "indexes help")))
        assertEquals("you are sol", ctx.personaSystemPrompt)
        assertEquals(1, ctx.comments.size)
        assertEquals("indexes help", ctx.comments[0].body)
        assertEquals("sol", ctx.comments[0].authorId)
    }

    @Test
    fun `firewall - the assembled context has no structural place for a vote`() {
        // ContextComment only exposes authorId + body — there is no vote field to leak through.
        // This is the structural guarantee behind the +1 firewall (the acceptance suite proves the
        // behavioural half by spying on what the LlmClient received).
        val ctx = ContextAssembler.assemble("sys", listOf(comment("owner", "a comment")))
        val fields = ctx.comments.first()::class.members.map { it.name }
        assertTrue(fields.none { it.contains("vote", ignoreCase = true) })
    }
}
