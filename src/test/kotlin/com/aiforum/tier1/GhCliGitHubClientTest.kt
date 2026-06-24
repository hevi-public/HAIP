package com.aiforum.tier1

import com.aiforum.github.GhCliGitHubClient
import com.aiforum.github.GitHubResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-1: the un-fakeable plumbing of [GhCliGitHubClient] — argv construction, exit-code/error mapping,
 * and the read-only invariant — exercised by substituting the [exec] seam with canned results instead of
 * spawning the real `gh` binary. The pure parsing is proven separately in GitHubJsonTest.
 */
@Tag("tier1")
class GhCliGitHubClientTest {

    private val repoJson = """
        {"nameWithOwner":"hevi-public/haip","description":"AI forum","url":"https://github.com/hevi-public/haip",
         "defaultBranchRef":{"name":"main"},"stargazerCount":7,"issues":{"totalCount":3},"pullRequests":{"totalCount":2}}
    """.trimIndent()
    private val prJson = """[{"number":12,"title":"Add gh MCP","author":{"login":"octocat"},"url":"u","isDraft":false,"createdAt":"2026-06-20T10:00:00Z"}]"""
    private val issueJson = """[{"number":5,"title":"Bug","author":{"login":"hubot"},"url":"u","createdAt":"2026-06-19T10:00:00Z"}]"""

    /**
     * A client whose [exec] returns scripted output keyed off the gh subcommand, and which records every
     * argv it was asked to run (so we can assert the read-only invariant and that disabled never spawns).
     * `repoExit` / `prExits` let a test force a non-zero exit on a specific call.
     */
    private inner class FakeGh(
        enabled: Boolean,
        repo: String = "",
        private val repoExit: Int = 0,
        private val failPrSpawn: Boolean = false,
    ) : GhCliGitHubClient(enabled = enabled, repo = repo) {
        val argvs = mutableListOf<List<String>>()
        override fun exec(argv: List<String>): ExecResult {
            argvs += argv
            return when {
                argv.getOrNull(0) == "repo" -> ExecResult.Completed(repoExit, if (repoExit == 0) repoJson else "", if (repoExit == 0) "" else "gh: Not Found (HTTP 404)")
                argv.getOrNull(0) == "pr" -> if (failPrSpawn) ExecResult.Failed("boom") else ExecResult.Completed(0, prJson, "")
                argv.getOrNull(0) == "issue" -> ExecResult.Completed(0, issueJson, "")
                else -> ExecResult.Failed("unexpected argv: $argv")
            }
        }
    }

    @Test
    fun `enabled composes repo summary plus open PRs and issues`() {
        val result = FakeGh(enabled = true, repo = "hevi-public/haip").overview()
        val ok = assertInstanceOf(GitHubResult.Ok::class.java, result)
        assertEquals("hevi-public/haip", ok.overview.repo.nameWithOwner)
        assertEquals("main", ok.overview.repo.defaultBranch)
        assertEquals(1, ok.overview.pulls.size)
        assertEquals(12, ok.overview.pulls.first().number)
        assertEquals(1, ok.overview.issues.size)
        assertEquals(5, ok.overview.issues.first().number)
    }

    @Test
    fun `every gh invocation is one of the read-only commands, and a pinned repo is passed through`() {
        val client = FakeGh(enabled = true, repo = "hevi-public/haip")
        client.overview()
        // Only repo view / pr list / issue list, never a mutating verb.
        val heads = client.argvs.map { it.take(2) }
        assertTrue(heads.contains(listOf("repo", "view")))
        assertTrue(heads.contains(listOf("pr", "list")))
        assertTrue(heads.contains(listOf("issue", "list")))
        assertEquals(3, client.argvs.size)
        // repo view takes the repo positionally; pr/issue list take it via --repo.
        assertTrue(client.argvs.first { it[0] == "repo" }.contains("hevi-public/haip"))
        assertTrue(client.argvs.first { it[0] == "pr" }.containsAll(listOf("--repo", "hevi-public/haip", "--state", "open")))
    }

    @Test
    fun `disabled returns Unavailable without ever spawning gh`() {
        val client = FakeGh(enabled = false)
        val result = client.overview()
        assertInstanceOf(GitHubResult.Unavailable::class.java, result)
        assertTrue(client.argvs.isEmpty(), "nothing should be spawned when disabled")
    }

    @Test
    fun `a non-zero repo view exit surfaces as Unavailable carrying the gh error`() {
        val result = FakeGh(enabled = true, repo = "x/y", repoExit = 1).overview()
        val unavailable = assertInstanceOf(GitHubResult.Unavailable::class.java, result)
        assertTrue(unavailable.reason.contains("404"), "reason should include gh's stderr line: ${unavailable.reason}")
    }

    @Test
    fun `a failed PR list is best-effort - the repo summary still renders with no PRs`() {
        val result = FakeGh(enabled = true, repo = "x/y", failPrSpawn = true).overview()
        val ok = assertInstanceOf(GitHubResult.Ok::class.java, result)
        assertTrue(ok.overview.pulls.isEmpty())
        assertEquals(1, ok.overview.issues.size) // issues still fetched
    }

    @Test
    fun `the real spawn path maps a missing gh binary to Unavailable rather than throwing`() {
        // Uses the production exec() (no override) with a binary that doesn't exist, so the ProcessBuilder
        // IOException → Unavailable path is exercised hermetically — no real gh, no network.
        val client = GhCliGitHubClient(enabled = true, repo = "o/r", command = "haip-nonexistent-gh-binary")
        val result = client.overview()
        val unavailable = assertInstanceOf(GitHubResult.Unavailable::class.java, result)
        assertTrue(unavailable.reason.contains("couldn't be launched"), unavailable.reason)
    }
}
