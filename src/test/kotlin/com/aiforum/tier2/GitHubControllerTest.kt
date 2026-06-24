package com.aiforum.tier2

import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubOverview
import com.aiforum.github.GitHubResult
import com.aiforum.github.Issue
import com.aiforum.github.PullRequest
import com.aiforum.github.RepoSummary
import com.aiforum.web.GitHubController
import com.aiforum.web.GitHubPageView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-2: the real [GitHubController] running against a fake [GitHubClient] seam (the one mock level) and a
 * fixed clock. Proves the controller maps an Ok snapshot into the page view (relativising timestamps) and
 * an Unavailable result into the off-state, with no Spring context needed.
 */
@Tag("tier2")
class GitHubControllerTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-24T12:00:00Z"), ZoneOffset.UTC)

    private class StubClient(private val result: GitHubResult) : GitHubClient {
        override fun overview() = result
    }

    private fun render(result: GitHubResult): GitHubPageView {
        val model = ExtendedModelMap()
        val view = GitHubController(StubClient(result), clock).page(model)
        assertEquals("github", view)
        return model.getAttribute("page") as GitHubPageView
    }

    @Test
    fun `an Ok snapshot becomes an available page with relativised timestamps`() {
        val overview = GitHubOverview(
            repo = RepoSummary("hevi-public/haip", "AI forum", "https://x", "main", 7, 3, 2),
            pulls = listOf(PullRequest(12, "Add gh MCP", "octocat", "u", isDraft = true, createdAt = "2026-06-22T12:00:00Z")),
            issues = listOf(Issue(5, "Bug", "hubot", "u", createdAt = "2026-06-24T11:00:00Z")),
        )
        val page = render(GitHubResult.Ok(overview))

        assertTrue(page.available)
        assertEquals("hevi-public/haip", page.repo?.nameWithOwner)
        assertEquals(1, page.pulls.size)
        assertEquals(12, page.pulls.first().number)
        assertTrue(page.pulls.first().isDraft)
        assertEquals("2d", page.pulls.first().ago)   // 2026-06-22 → 2026-06-24
        assertEquals("1h", page.issues.first().ago)   // 11:00 → 12:00
    }

    @Test
    fun `an Unavailable result becomes the off-state page carrying the reason`() {
        val page = render(GitHubResult.Unavailable("GitHub integration is off."))
        assertFalse(page.available)
        assertEquals("GitHub integration is off.", page.reason)
        assertNull(page.repo)
        assertTrue(page.pulls.isEmpty())
    }

    @Test
    fun `an unparseable timestamp falls back to the raw string instead of throwing`() {
        val overview = GitHubOverview(
            repo = RepoSummary("o/r", null, "u", "main", 0, 0, 0),
            pulls = listOf(PullRequest(1, "t", "a", "u", isDraft = false, createdAt = "not-a-date")),
            issues = emptyList(),
        )
        val page = render(GitHubResult.Ok(overview))
        assertEquals("not-a-date", page.pulls.first().ago)
    }
}
