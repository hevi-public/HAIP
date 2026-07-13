package com.aiforum.tier0

import com.aiforum.github.ChangedFile
import com.aiforum.github.PrThreadFormat
import com.aiforum.github.PullDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: pure formatting of a [PullDetail] into a thread's opening post — title + markdown body. No IO; the
 * diff-truncation budget and the section assembly are proven here so the acceptance test only has to show
 * the formatted body reached the page.
 */
@Tag("tier0")
class PrThreadFormatTest {

    private fun pull(
        number: Int = 42,
        title: String = "Batch the comment query",
        body: String = "Fixes the N+1 in the comment tree.",
        changedFiles: List<ChangedFile> = listOf(ChangedFile("src/CommentRepository.kt", 12, 3)),
        diff: String = "diff --git a/src/CommentRepository.kt b/src/CommentRepository.kt\n+added\n-removed",
        isDraft: Boolean = false,
    ) = PullDetail(
        number = number, title = title, author = "octocat",
        url = "https://github.com/o/r/pull/$number", state = "OPEN", isDraft = isDraft,
        body = body, baseRef = "main", headRef = "feature", headSha = "deadbeef",
        changedFiles = changedFiles, diff = diff,
    )

    @Test
    fun `title is the number and PR title`() {
        assertEquals("#42 — Batch the comment query", PrThreadFormat.title(pull()))
    }

    @Test
    fun `body leads with the description, then meta, changed files, and a fenced diff`() {
        val body = PrThreadFormat.body(pull())
        assertTrue(body.startsWith("Fixes the N+1 in the comment tree."), "description leads:\n$body")
        assertTrue(body.contains("[PR #42 on GitHub](https://github.com/o/r/pull/42)"), "links to the PR:\n$body")
        assertTrue(body.contains("`main ← feature`"), "shows branch direction:\n$body")
        assertTrue(body.contains("## Changed files (1)"), "has a changed-files section:\n$body")
        assertTrue(body.contains("- `src/CommentRepository.kt` +12/-3"), "lists the file with counts:\n$body")
        assertTrue(body.contains("```diff"), "fences the diff as a diff block:\n$body")
        assertTrue(body.contains("+added"), "carries the diff content:\n$body")
    }

    @Test
    fun `a blank description is omitted so the body opens on the meta line`() {
        val body = PrThreadFormat.body(pull(body = "  "))
        assertTrue(body.startsWith("**[PR #42 on GitHub]"), "no leading blank lines when description is empty:\n$body")
    }

    @Test
    fun `a draft is flagged on the meta line`() {
        assertTrue(PrThreadFormat.body(pull(isDraft = true)).contains("· draft"))
        assertFalse(PrThreadFormat.body(pull(isDraft = false)).contains("· draft"))
    }

    @Test
    fun `no changed files means no changed-files section`() {
        val body = PrThreadFormat.body(pull(changedFiles = emptyList()))
        assertFalse(body.contains("## Changed files"), "section omitted when there are no files:\n$body")
    }

    @Test
    fun `a diff over the line budget is truncated with a note linking to the PR`() {
        val bigDiff = (1..(PrThreadFormat.DIFF_LINE_BUDGET + 50)).joinToString("\n") { "+line $it" }
        val body = PrThreadFormat.body(pull(diff = bigDiff))
        assertTrue(body.contains("+line ${PrThreadFormat.DIFF_LINE_BUDGET}"), "keeps lines up to the budget")
        assertFalse(body.contains("+line ${PrThreadFormat.DIFF_LINE_BUDGET + 1}"), "drops lines past the budget")
        assertTrue(body.contains("Diff truncated to ${PrThreadFormat.DIFF_LINE_BUDGET} of ${PrThreadFormat.DIFF_LINE_BUDGET + 50} lines"), "explains the truncation:\n$body")
        assertTrue(body.contains("https://github.com/o/r/pull/42/files"), "links to the full diff")
    }

    @Test
    fun `a blank diff means no diff section`() {
        val body = PrThreadFormat.body(pull(diff = ""))
        assertFalse(body.contains("```diff"), "no fence when there's no diff:\n$body")
    }
}
