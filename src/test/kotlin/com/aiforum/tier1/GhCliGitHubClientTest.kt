package com.aiforum.tier1

import ch.qos.logback.classic.Level
import com.aiforum.github.GhCliGitHubClient
import com.aiforum.github.GitHubResult
import com.aiforum.testsupport.LogCapture
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
        private val versionExit: Int = 0,
        private val versionSpawnFails: Boolean = false,
    ) : GhCliGitHubClient(enabled = enabled, repo = repo) {
        val argvs = mutableListOf<List<String>>()
        override fun exec(argv: List<String>): ExecResult {
            argvs += argv
            return when {
                argv.getOrNull(0) == "--version" ->
                    if (versionSpawnFails) ExecResult.Failed("the `gh` CLI couldn't be launched (gh)")
                    else ExecResult.Completed(versionExit, "gh version 2.40.0", if (versionExit == 0) "" else "boom")
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
    fun `availabilityError is null when gh --version succeeds`() {
        assertEquals(null, FakeGh(enabled = true).availabilityError())
    }

    @Test
    fun `availabilityError reports the problem when gh cannot be launched`() {
        val problem = FakeGh(enabled = true, versionSpawnFails = true).availabilityError()
        assertTrue(problem != null && problem.contains("couldn't be launched"), "got: $problem")
    }

    @Test
    fun `availabilityError reports a non-zero gh --version exit`() {
        val problem = FakeGh(enabled = true, versionExit = 1).availabilityError()
        assertTrue(problem != null && problem.contains("exited 1"), "got: $problem")
    }

    @Test
    fun `the startup availability check probes gh --version when enabled`() {
        val client = FakeGh(enabled = true)
        client.logStartupAvailability()
        assertTrue(client.argvs.contains(listOf("--version")), "expected a gh --version probe at startup")
    }

    @Test
    fun `the startup availability check is skipped entirely when disabled`() {
        val client = FakeGh(enabled = false)
        client.logStartupAvailability()
        assertTrue(client.argvs.isEmpty(), "a disabled integration must not probe gh at startup")
    }

    // --- logging is IO: pin the WARN/INFO/DEBUG lines — prose AND structured event id + fields — as a
    //     contract (see the bdd-tiered-testing skill, "Logging is IO — assert it") ---

    @Test
    fun `a non-zero repo view exit logs a single WARN carrying the off-state reason and structured fields`() {
        LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
            FakeGh(enabled = true, repo = "x/y", repoExit = 1).overview()
            // Human prose:
            assertEquals(
                listOf("/github is unavailable: gh repo view failed: gh: Not Found (HTTP 404)"),
                logs.warns(),
            )
            // Machine-readable contract:
            val e = logs.withEvent("gh.unavailable").single()
            assertEquals(Level.WARN, e.level)
            assertEquals("gh repo view failed: gh: Not Found (HTTP 404)", logs.keyValue(e, "reason"))
        }
    }

    @Test
    fun `a best-effort pr list failure logs the gh-list-failed event at DEBUG and never WARNs`() {
        LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
            FakeGh(enabled = true, repo = "x/y", failPrSpawn = true).overview()
            assertEquals(listOf("gh pr list failed: boom"), logs.debugs())
            assertTrue(logs.warns().isEmpty(), "best-effort failures must not WARN; got: ${logs.warns()}")
            val e = logs.withEvent("gh.list.failed").single()
            assertEquals(Level.DEBUG, e.level)
            assertEquals("pr", logs.keyValue(e, "list"))
            assertEquals("boom", logs.keyValue(e, "detail"))
        }
    }

    @Test
    fun `the startup probe logs the gh-startup-unavailable event with the reason when gh is unavailable`() {
        LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
            FakeGh(enabled = true, versionSpawnFails = true).logStartupAvailability()
            assertEquals(
                listOf("GitHub integration is enabled but the `gh` CLI couldn't be launched (gh) — /github will show an error until this is fixed."),
                logs.warns(),
            )
            val e = logs.withEvent("gh.startup.unavailable").single()
            assertEquals(Level.WARN, e.level)
            assertEquals("gh", logs.keyValue(e, "command"))
            assertTrue(logs.keyValue(e, "reason")!!.contains("couldn't be launched"))
        }
    }

    @Test
    fun `the startup probe logs the gh-startup-ok event at INFO when gh is available`() {
        LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
            FakeGh(enabled = true).logStartupAvailability()
            assertEquals(listOf("GitHub integration enabled; `gh` is available."), logs.infos())
            assertTrue(logs.warns().isEmpty())
            val e = logs.withEvent("gh.startup.ok").single()
            assertEquals(Level.INFO, e.level)
            assertEquals("gh", logs.keyValue(e, "command"))
        }
    }

    @Test
    fun `a disabled integration logs nothing at startup`() {
        LogCapture.on(GhCliGitHubClient::class.java).use { logs ->
            FakeGh(enabled = false).logStartupAvailability()
            assertTrue(logs.events.isEmpty(), "a disabled integration must stay silent; got: ${logs.events}")
        }
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
