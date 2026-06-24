package com.aiforum.tier0

import com.aiforum.github.GitHubJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: pure parsing of `gh ... --json` envelopes into the GitHub view types. No IO — canned strings in,
 * data classes out — so every field-extraction branch (nested author/branch/count objects, draft flag,
 * null description, the empty list) is covered without the real `gh` binary.
 */
@Tag("tier0")
class GitHubJsonTest {

    @Test
    fun `parseRepo pulls the summary fields including nested branch and counts`() {
        val json = """
            {"nameWithOwner":"hevi-public/haip","description":"AI forum","url":"https://github.com/hevi-public/haip",
             "defaultBranchRef":{"name":"main"},"stargazerCount":7,"issues":{"totalCount":3},"pullRequests":{"totalCount":2}}
        """.trimIndent()
        val repo = GitHubJson.parseRepo(json)
        assertEquals("hevi-public/haip", repo.nameWithOwner)
        assertEquals("AI forum", repo.description)
        assertEquals("https://github.com/hevi-public/haip", repo.url)
        assertEquals("main", repo.defaultBranch)
        assertEquals(7, repo.stars)
        assertEquals(3, repo.openIssues)
        assertEquals(2, repo.openPrs)
    }

    @Test
    fun `parseRepo treats a blank description as null and tolerates missing nested objects`() {
        val repo = GitHubJson.parseRepo("""{"nameWithOwner":"o/r","description":"","url":"u","stargazerCount":0}""")
        assertNull(repo.description)
        assertEquals("", repo.defaultBranch)
        assertEquals(0, repo.openPrs)
        assertEquals(0, repo.openIssues)
    }

    @Test
    fun `parsePulls maps each row and flattens the author login`() {
        val json = """
            [{"number":12,"title":"Add gh MCP","author":{"login":"octocat"},
              "url":"https://github.com/o/r/pull/12","isDraft":true,"createdAt":"2026-06-20T10:00:00Z"}]
        """.trimIndent()
        val pulls = GitHubJson.parsePulls(json)
        assertEquals(1, pulls.size)
        val pr = pulls.first()
        assertEquals(12, pr.number)
        assertEquals("Add gh MCP", pr.title)
        assertEquals("octocat", pr.author)
        assertTrue(pr.isDraft)
        assertEquals("2026-06-20T10:00:00Z", pr.createdAt)
    }

    @Test
    fun `a missing author falls back to ghost rather than throwing`() {
        val pulls = GitHubJson.parsePulls("""[{"number":1,"title":"t","url":"u","isDraft":false,"createdAt":"2026-06-20T10:00:00Z"}]""")
        assertEquals("ghost", pulls.first().author)
    }

    @Test
    fun `parseIssues maps rows and an empty array yields an empty list`() {
        assertTrue(GitHubJson.parseIssues("[]").isEmpty())
        val issues = GitHubJson.parseIssues("""[{"number":5,"title":"Bug","author":{"login":"hubot"},"url":"u","createdAt":"2026-06-19T10:00:00Z"}]""")
        assertEquals(1, issues.size)
        assertEquals(5, issues.first().number)
        assertEquals("hubot", issues.first().author)
    }
}
