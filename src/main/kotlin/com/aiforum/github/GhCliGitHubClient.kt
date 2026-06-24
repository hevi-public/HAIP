package com.aiforum.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production [GitHubClient]: wraps the `gh` CLI via [ProcessBuilder]. It is the single un-fakeable IO seam
 * for the /github page; the pure classification of its output lives in [GitHubJson], so what remains here
 * is just spawning, stream capture, and the deadline (mirrors ProcessLlmClient).
 *
 * READ-ONLY by construction. The only commands it ever builds are `repo view`, `pr list`, and `issue list`
 * — all reads. [requireReadOnly] is a defence-in-depth re-check on every argv before it reaches `gh`, so a
 * future edit can't silently introduce a mutating subcommand.
 *
 * Inert unless `aiforum.github.enabled=true`: with the flag off (the default, including under the `test`
 * profile) [overview] returns [GitHubResult.Unavailable] without spawning anything, so the bean is safe to
 * have in every context and the acceptance suite never shells out.
 *
 * `open` (and `protected open fun exec`) so the Tier-1 test can substitute a controlled subprocess and
 * exercise the argv-building / error-mapping without the real `gh` binary or a network call.
 */
@Component
open class GhCliGitHubClient(
    @Value("\${aiforum.github.enabled:false}") private val enabled: Boolean = false,
    // Blank => let `gh` infer the repo from the working directory; usually you want to pin "OWNER/REPO"
    // here since the app's working dir isn't a clone.
    @Value("\${aiforum.github.repo:}") private val repo: String = "",
    @Value("\${aiforum.github.command:gh}") private val command: String = "gh",
    @Value("\${aiforum.github.list-limit:10}") private val listLimit: Int = 10,
    @Value("\${aiforum.github.timeout-seconds:20}") private val timeoutSeconds: Long = 20,
) : GitHubClient {

    /** The outcome of one `gh` invocation: it either ran (with an exit code + captured streams) or never
     *  started / timed out (the binary is missing, or it overran the deadline). */
    protected sealed interface ExecResult {
        data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : ExecResult
        data class Failed(val message: String) : ExecResult
    }

    override fun overview(): GitHubResult {
        if (!enabled) {
            return GitHubResult.Unavailable(
                "GitHub integration is off. Set aiforum.github.enabled=true (needs the `gh` CLI installed and authenticated with `gh auth login`).",
            )
        }

        val repoArg = if (repo.isNotBlank()) listOf(repo) else emptyList()
        val repoFlag = if (repo.isNotBlank()) listOf("--repo", repo) else emptyList()
        val limit = listLimit.coerceIn(1, 100).toString()

        // The repo summary is the anchor of the page; if it can't be fetched there's nothing to show.
        val repoSummary = when (val r = run(listOf("repo", "view") + repoArg + listOf("--json", GitHubJson.REPO_FIELDS))) {
            is ExecResult.Failed -> return GitHubResult.Unavailable(r.message)
            is ExecResult.Completed ->
                if (r.exitCode != 0) return GitHubResult.Unavailable(ghError("gh repo view", r.stderr))
                else parseOr(null) { GitHubJson.parseRepo(r.stdout) } ?: return GitHubResult.Unavailable("Couldn't parse `gh repo view` output.")
        }

        // PRs and issues are best-effort: if either list fails we still render the repo summary with an
        // empty section rather than failing the whole page.
        val pulls = when (val r = run(listOf("pr", "list") + repoFlag + listOf("--state", "open", "--limit", limit, "--json", GitHubJson.PR_FIELDS))) {
            is ExecResult.Completed -> if (r.exitCode == 0) parseOr(emptyList()) { GitHubJson.parsePulls(r.stdout) } else emptyList()
            is ExecResult.Failed -> emptyList()
        }
        val issues = when (val r = run(listOf("issue", "list") + repoFlag + listOf("--state", "open", "--limit", limit, "--json", GitHubJson.ISSUE_FIELDS))) {
            is ExecResult.Completed -> if (r.exitCode == 0) parseOr(emptyList()) { GitHubJson.parseIssues(r.stdout) } else emptyList()
            is ExecResult.Failed -> emptyList()
        }

        return GitHubResult.Ok(GitHubOverview(repoSummary, pulls, issues))
    }

    /** Guard the argv, then run it. Keeps the read-only check on the single path to `gh`. */
    private fun run(argv: List<String>): ExecResult = exec(requireReadOnly(argv))

    /**
     * Spawn `gh` with a fixed argv (no shell, so nothing can be smuggled through quoting) and capture its
     * output under a bounded deadline. Overridden in tests with a controlled stand-in.
     */
    protected open fun exec(argv: List<String>): ExecResult {
        val process = try {
            ProcessBuilder(listOf(command) + argv)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            // Most commonly: the `gh` binary isn't on PATH.
            return ExecResult.Failed("The `gh` CLI couldn't be launched ($command): ${e.message}. Install it from https://cli.github.com and run `gh auth login`.")
        }

        // `gh --json` output is bounded (capped by --limit), so draining stdout to EOF before waitFor is
        // safe and simpler than the daemon-drain dance ProcessLlmClient needs for a chatty model.
        val stdout = process.inputStream.use { it.readBytes().decodeToString() }
        val stderr = process.errorStream.use { it.readBytes().decodeToString() }

        return if (!process.waitFor(timeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ExecResult.Failed("`gh` timed out after ${timeoutSeconds}s.")
        } else {
            ExecResult.Completed(process.exitValue(), stdout, stderr)
        }
    }

    private fun <T> parseOr(fallback: T, parse: () -> T): T =
        try {
            parse()
        } catch (_: Exception) {
            fallback
        }

    /** A compact, user-facing message from a non-zero `gh` exit — the first non-blank stderr line (gh's
     *  auth/not-found errors are one-liners), or a generic fallback. */
    private fun ghError(what: String, stderr: String): String {
        val line = stderr.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
        return if (line != null) "$what failed: $line" else "$what failed (is `gh` authenticated and the repo reachable?)."
    }

    private companion object {
        // The only top-level commands this client may ever invoke, each with its single read subcommand.
        val ALLOWED: Map<String, String> = mapOf("repo" to "view", "pr" to "list", "issue" to "list")
    }

    /** Defence-in-depth: every argv handed to `gh` must be one of the read commands above. */
    private fun requireReadOnly(argv: List<String>): List<String> {
        val command = argv.getOrNull(0)
        val sub = argv.getOrNull(1)
        require(command != null && ALLOWED[command] == sub) {
            "internal: '$command $sub' is not an allowed read-only gh command"
        }
        return argv
    }
}
