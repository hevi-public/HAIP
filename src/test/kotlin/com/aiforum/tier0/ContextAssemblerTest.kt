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

    private fun comment(author: String, body: String, parentId: String? = null, depth: Int = 0) =
        Comment("id-$body", "t", parentId, author, body, GenerationState.POSTED, null, depth)

    @Test
    fun `context carries comment bodies and authors`() {
        val ctx = ContextAssembler.assemble("you are sol", listOf(comment("sol", "indexes help")))
        assertEquals("you are sol", ctx.personaSystemPrompt)
        assertEquals(1, ctx.comments.size)
        assertEquals("indexes help", ctx.comments[0].body)
        assertEquals("sol", ctx.comments[0].authorId)
    }

    @Test
    fun `context carries the structural fields the transcript renderer needs`() {
        // id/parentId/depth flow through so the prompt can show reply shape (indentation + reply tags).
        // They are structural only — see the firewall test below for what must NOT come along.
        val ctx = ContextAssembler.assemble("sys", listOf(comment("bob", "a reply", parentId = "id-root", depth = 2)))
        val cc = ctx.comments.single()
        assertEquals("id-a reply", cc.id)
        assertEquals("id-root", cc.parentId)
        assertEquals(2, cc.depth)
    }

    @Test
    fun `context carries the reply target so the prompt can mark which node to answer`() {
        // The owner's reply target flows through as targetId; renderPrompt uses it to point the persona
        // at the exact node (vs guessing "the most recent line", which misfires in whole-thread scope).
        val ctx = ContextAssembler.assemble(
            "sys",
            listOf(comment("owner", "the question"), comment("vex", "a tangent")),
            targetId = "id-the question",
        )
        assertEquals("id-the question", ctx.targetId)
    }

    @Test
    fun `target defaults to null when none is given so opening a thread takes the fallback`() {
        assertEquals(null, ContextAssembler.assemble("sys", listOf(comment("sol", "hi"))).targetId)
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
