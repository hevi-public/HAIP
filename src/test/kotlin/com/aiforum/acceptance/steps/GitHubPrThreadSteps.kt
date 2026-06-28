package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableGitHubClient
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.github.ChangedFile
import com.aiforum.github.PullDetail
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for "Discuss this PR" (plan_docs/github-pr-threads.md): program the @Primary [ScriptableGitHubClient]
 * with an in-depth PR, POST the discuss endpoint, and assert the forum thread it creates carries the change
 * and gets summarised by the room. The LLM reply is scripted via the shared "the LLM will respond with …"
 * step (CommonSteps); with a single seeded persona the dispatcher short-circuits, so one scripted reply IS
 * the summary.
 */
class GitHubPrThreadSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
    private val github: ScriptableGitHubClient,
) {
    private val discussedThreadIds = mutableListOf<String?>()

    @Given("an in-depth pull request #{int} {string} by {string} described as {string} changing {string} with diff:")
    fun programPull(number: Int, title: String, author: String, description: String, path: String, diff: String) {
        val adds = diff.lines().count { it.startsWith("+") && !it.startsWith("+++") }
        val dels = diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
        github.pullDetails[number] = PullDetail(
            number = number, title = title, author = author,
            url = "https://github.com/o/r/pull/$number", state = "OPEN", isDraft = false,
            body = description, baseRef = "main", headRef = "feature", headSha = "sha-$number",
            changedFiles = listOf(ChangedFile(path, adds, dels)), diff = diff,
        )
    }

    @When("the owner clicks Discuss on pull request #{int}")
    fun discuss(number: Int) {
        discussOnce(number)
    }

    @When("the owner clicks Discuss on pull request #{int} again")
    fun discussAgain(number: Int) {
        discussOnce(number)
    }

    private fun discussOnce(number: Int) {
        val resp = http.post("/github/pr/$number/discuss")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        // PRG: the discuss endpoint redirects to /threads/{id}. Read the thread id from the Location header,
        // or — if the HTTP client auto-followed — from the rendered thread page. Robust to either.
        val threadId = resp.headers.location?.toString()?.let { Regex("""/threads/([^/?#]+)""").find(it)?.groupValues?.get(1) }
            ?: resp.body?.let { Regex("""data-thread-id="([^"]+)"""").find(it)?.groupValues?.get(1) }
        world.threadId = threadId
        discussedThreadIds += threadId
    }

    @Then("the thread carries a reply reading {string}")
    fun threadCarriesReply(text: String) {
        // Settle the async create-time summon (mirrors the browser's htmx poll) before reading the page.
        settle.awaitAllSettled(settle.awaitRoomDrafts(world.threadId ?: ""))
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(Html.contains(body, text), "expected a reply reading \"$text\" in:\n$body")
    }

    @Then("both discussions opened the same thread")
    fun bothDiscussionsSameThread() {
        assertEquals(2, discussedThreadIds.size, "expected two discuss clicks")
        val first = discussedThreadIds[0]
        assertNotNull(first, "the first discuss should have created a thread")
        assertEquals(first, discussedThreadIds[1], "the second discuss must reuse the first thread, not create a new one")
    }
}
