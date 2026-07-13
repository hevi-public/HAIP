package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableGitHubClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.github.Issue
import com.aiforum.github.PullRequest
import com.aiforum.github.RepoSummary
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the read-only /github page. They program the @Primary [ScriptableGitHubClient] seam (the real
 * gh adapter is inert under test) and assert on the page's stable data-* hooks over HTTP — never the gh
 * binary, never the network.
 */
class GitHubPageSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val github: ScriptableGitHubClient,
) {
    // A timestamp comfortably before the fixed test clock (2026-01-01T12:00:00Z) so it relativises cleanly.
    private val createdAt = "2026-01-01T09:00:00Z"

    private fun body(): String = world.lastBody ?: ""

    @Given("the GitHub integration is unavailable with reason {string}")
    fun integrationUnavailable(reason: String) {
        github.repo = null
        github.unavailableReason = reason
    }

    @Given("the GitHub integration reports the repository {string}")
    fun reportsRepository(nameWithOwner: String) {
        github.repo = RepoSummary(
            nameWithOwner = nameWithOwner,
            description = "a repository",
            url = "https://github.com/$nameWithOwner",
            defaultBranch = "main",
            stars = 0,
            openIssues = 0,
            openPrs = 0,
        )
    }

    @Given("an open pull request #{int} {string} by {string}")
    fun openPullRequest(number: Int, title: String, author: String) {
        github.pulls.add(
            PullRequest(number, title, author, "https://github.com/x/y/pull/$number", isDraft = false, createdAt = createdAt),
        )
    }

    @Given("an open issue #{int} {string} by {string}")
    fun openIssue(number: Int, title: String, author: String) {
        github.issues.add(
            Issue(number, title, author, "https://github.com/x/y/issues/$number", createdAt = createdAt),
        )
    }

    @When("the owner visits the GitHub page")
    fun visitGitHubPage() {
        val resp = http.get("/github")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the GitHub page shows the unavailable notice {string}")
    fun showsUnavailable(reason: String) {
        assertTrue(
            Html.hasAttr(body(), "data-github-unavailable", "true"),
            "expected data-github-unavailable=\"true\" in:\n${body()}",
        )
        assertTrue(Html.contains(body(), reason), "expected the page to show \"$reason\" in:\n${body()}")
    }

    @Then("the GitHub page shows the repository {string}")
    fun showsRepository(nameWithOwner: String) {
        assertTrue(
            Html.hasAttr(body(), "data-github-repo", nameWithOwner),
            "expected data-github-repo=\"$nameWithOwner\" in:\n${body()}",
        )
    }

    @Then("the GitHub page lists pull request #{int}")
    fun listsPullRequest(number: Int) {
        assertTrue(
            Html.hasAttr(body(), "data-github-pr", number.toString()),
            "expected data-github-pr=\"$number\" in:\n${body()}",
        )
    }

    @Then("the GitHub page lists issue #{int}")
    fun listsIssue(number: Int) {
        assertTrue(
            Html.hasAttr(body(), "data-github-issue", number.toString()),
            "expected data-github-issue=\"$number\" in:\n${body()}",
        )
    }

    @Then("the GitHub page shows no open pull requests")
    fun showsNoPulls() {
        assertTrue(
            Html.hasAttr(body(), "data-github-pulls-empty", "true"),
            "expected data-github-pulls-empty=\"true\" in:\n${body()}",
        )
    }

    @Then("the GitHub page shows no open issues")
    fun showsNoIssues() {
        assertTrue(
            Html.hasAttr(body(), "data-github-issues-empty", "true"),
            "expected data-github-issues-empty=\"true\" in:\n${body()}",
        )
    }
}
