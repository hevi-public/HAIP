package com.aiforum.github

/**
 * The read-only GitHub seam (Option A — the human-facing /github page). Mirrors the [com.aiforum.llm.LlmClient]
 * shape: a narrow interface whose single production adapter ([GhCliGitHubClient]) shells out to the `gh`
 * CLI, with the pure parsing of `gh ... --json` output split out into [GitHubJson] so the un-fakeable part
 * stays tiny (see the bdd-tiered-testing skill).
 *
 * Everything here is READ-ONLY by construction: the adapter only ever runs `repo view` / `pr list` /
 * `issue list`. There is no method that mutates anything on GitHub. This is the *backend's own* path to
 * GitHub data — it does NOT go through the `gh-readonly` MCP server (that server is for LLM tool-callers;
 * the page is for humans).
 */
interface GitHubClient {
    /** Fetch a read-only snapshot of the configured repository, or explain why it isn't available. */
    fun overview(): GitHubResult
}

/** Repo-level summary shown at the top of the page (from `gh repo view --json`). */
data class RepoSummary(
    val nameWithOwner: String,
    val description: String?,
    val url: String,
    val defaultBranch: String,
    val stars: Int,
    val openIssues: Int,
    val openPrs: Int,
)

/** One open pull request row (from `gh pr list --json`). `createdAt` is a raw ISO-8601 instant; the web
 *  layer relativises it so time formatting stays out of this IO-adjacent layer. */
data class PullRequest(
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val isDraft: Boolean,
    val createdAt: String,
)

/** One open issue row (from `gh issue list --json`). */
data class Issue(
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val createdAt: String,
)

/** A read-only snapshot: the repo summary plus its open PRs and issues. */
data class GitHubOverview(
    val repo: RepoSummary,
    val pulls: List<PullRequest>,
    val issues: List<Issue>,
)

/**
 * The result of [GitHubClient.overview]. [Unavailable] is a first-class, user-facing state — the
 * integration is off by default, `gh` may be missing/unauthenticated, or the repo may be unreachable —
 * so the page renders a clear explanation rather than erroring.
 */
sealed interface GitHubResult {
    data class Ok(val overview: GitHubOverview) : GitHubResult
    data class Unavailable(val reason: String) : GitHubResult
}
