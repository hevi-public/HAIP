package com.aiforum.web

import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubResult
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.time.Instant

/** The repo summary block at the top of the /github page. */
data class GitHubRepoView(
    val nameWithOwner: String,
    val description: String?,
    val url: String,
    val defaultBranch: String,
    val stars: Int,
    val openIssues: Int,
    val openPrs: Int,
)

/** One open pull-request row, with the timestamp already relativised for display. */
data class GitHubPrView(
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val isDraft: Boolean,
    val ago: String,
)

/** One open issue row. */
data class GitHubIssueView(
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val ago: String,
)

/**
 * The whole-page view model. `available=false` carries a human-readable `reason` (integration off, `gh`
 * missing/unauthenticated, repo unreachable) and the lists are empty — the template renders the
 * explanation instead of the snapshot.
 */
data class GitHubPageView(
    val available: Boolean,
    val reason: String,
    val repo: GitHubRepoView?,
    val pulls: List<GitHubPrView>,
    val issues: List<GitHubIssueView>,
)

/**
 * Renders GET /github: a read-only snapshot of the configured repository (summary + open PRs + open
 * issues), fetched through the [GitHubClient] seam. Off by default — until `aiforum.github.enabled=true`
 * (and `gh` is installed + authenticated) the page shows a clear "not configured" state.
 *
 * Note on trust: PR/issue titles and author logins come from arbitrary GitHub contributors, i.e. they're
 * untrusted input. They're rendered through JTE `${}`, which HTML-escapes by default, so this is display-
 * only and not an injection vector.
 */
@Controller
class GitHubController(
    private val github: GitHubClient,
    private val clock: Clock,
) {
    @GetMapping("/github")
    fun page(model: Model): String {
        val page = when (val result = github.overview()) {
            is GitHubResult.Unavailable -> GitHubPageView(
                available = false,
                reason = result.reason,
                repo = null,
                pulls = emptyList(),
                issues = emptyList(),
            )
            is GitHubResult.Ok -> {
                val now = clock.instant()
                val o = result.overview
                GitHubPageView(
                    available = true,
                    reason = "",
                    repo = GitHubRepoView(
                        nameWithOwner = o.repo.nameWithOwner,
                        description = o.repo.description,
                        url = o.repo.url,
                        defaultBranch = o.repo.defaultBranch,
                        stars = o.repo.stars,
                        openIssues = o.repo.openIssues,
                        openPrs = o.repo.openPrs,
                    ),
                    pulls = o.pulls.map {
                        GitHubPrView(it.number, it.title, it.author, it.url, it.isDraft, agoOf(it.createdAt, now))
                    },
                    issues = o.issues.map {
                        GitHubIssueView(it.number, it.title, it.author, it.url, agoOf(it.createdAt, now))
                    },
                )
            }
        }
        model.addAttribute("page", page)
        return "github"
    }

    /** Relativise an ISO-8601 instant from `gh`; fall back to the raw string if it doesn't parse. */
    private fun agoOf(createdAt: String, now: Instant): String =
        try {
            RelativeTime.ago(Instant.parse(createdAt), now)
        } catch (_: Exception) {
            createdAt
        }
}
