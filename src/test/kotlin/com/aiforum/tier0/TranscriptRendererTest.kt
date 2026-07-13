package com.aiforum.tier0

import com.aiforum.domain.context.TranscriptRenderer
import com.aiforum.llm.ContextComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Tier-0: the prompt transcript is a pure function of the context — pin its shape here. No Spring, no IO. */
@Tag("tier0")
class TranscriptRendererTest {

    private fun c(id: String, author: String, body: String, parentId: String?, depth: Int) =
        ContextComment(id = id, authorId = author, body = body, parentId = parentId, depth = depth)

    @Test
    fun `a nested thread renders indentation plus reply tags pointing at the parent's ref`() {
        val transcript = TranscriptRenderer.render(
            listOf(
                c("a", "alice", "ship friday?", parentId = null, depth = 0),
                c("b", "bob", "CI is flaky", parentId = "a", depth = 1),
                c("d", "carol", "i can fix CI", parentId = "b", depth = 2),
                c("e", "dave", "friday's fine", parentId = "a", depth = 1),
            ),
        )
        assertEquals(
            """
            [#1] alice: ship friday?
              [#2 ↳ replying to #1] bob: CI is flaky
                [#3 ↳ replying to #2] carol: i can fix CI
              [#4 ↳ replying to #1] dave: friday's fine
            """.trimIndent(),
            transcript,
        )
    }

    @Test
    fun `the reply target is marked so the model answers it instead of the last line`() {
        // Whole-thread scope hands over the full tree; the node the owner is replying to ("b") is NOT the
        // last line ("e" sorts after it). The "← reply to this" marker names it unambiguously.
        val transcript = TranscriptRenderer.render(
            listOf(
                c("a", "alice", "ship friday?", parentId = null, depth = 0),
                c("b", "owner", "what about CI?", parentId = "a", depth = 1),
                c("e", "dave", "friday's fine", parentId = "a", depth = 1),
            ),
            targetId = "b",
        )
        assertEquals(
            """
            [#1] alice: ship friday?
              [#2 ↳ replying to #1] owner: what about CI? ← reply to this
              [#3 ↳ replying to #1] dave: friday's fine
            """.trimIndent(),
            transcript,
        )
    }

    @Test
    fun `refOf resolves the target's transcript ref and is null when the target is out of scope`() {
        val comments = listOf(
            c("a", "alice", "root", parentId = null, depth = 0),
            c("b", "bob", "kid", parentId = "a", depth = 1),
        )
        assertEquals(2, TranscriptRenderer.refOf(comments, "b"))
        assertEquals(null, TranscriptRenderer.refOf(comments, "missing"))
        assertEquals(null, TranscriptRenderer.refOf(comments, null))
    }

    @Test
    fun `a branch-only ancestor path with absolute depth is normalised to start at column zero`() {
        // ancestorPath() returns nodes carrying their real tree depth (here 2 and 3); the shallowest in
        // scope must anchor the left margin so a deep branch doesn't render pre-indented off the page.
        val transcript = TranscriptRenderer.render(
            listOf(
                c("p", "alice", "parent", parentId = "root", depth = 2),
                c("k", "bob", "kid", parentId = "p", depth = 3),
            ),
        )
        assertEquals(
            "[#1] alice: parent\n  [#2 ↳ replying to #1] bob: kid",
            transcript,
        )
    }

    @Test
    fun `a parent outside the rendered scope yields no reply tag`() {
        // Branch-only / sibling scopes can include a node whose parent isn't in the list; the tag is
        // dropped rather than dangling at an unknown ref.
        val transcript = TranscriptRenderer.render(
            listOf(c("k", "bob", "orphaned reply", parentId = "missing", depth = 1)),
        )
        assertEquals("[#1] bob: orphaned reply", transcript)
    }

    @Test
    fun `a multi-line body is flattened to a single line so the grid stays legible`() {
        val transcript = TranscriptRenderer.render(
            listOf(c("a", "alice", "line one\nline two\n", parentId = null, depth = 0)),
        )
        assertEquals("[#1] alice: line one line two", transcript)
        assertTrue(!transcript.contains("\n"), "body newlines must not survive into the transcript")
    }

    @Test
    fun `an empty context renders blank so the caller takes the new-thread branch`() {
        assertEquals("", TranscriptRenderer.render(emptyList()))
    }
}
