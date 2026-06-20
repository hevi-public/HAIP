package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for the home page: empty state and unread-count badge (§2).
 * The "a thread {string} exists" Given is in CommonSteps; these steps extend that contract.
 */
class HomeSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
) {
    @Given("there are no threads")
    fun noThreads() {
        // no-op: DatabaseResetHooks already deletes all rows before every scenario
    }

    @Given("the thread has {int} replies unread by the owner")
    fun threadHasUnreadReplies(count: Int) {
        val threadId = world.threadId ?: error("no thread in ScenarioWorld — use 'a thread ... exists' first")
        repeat(count) { i ->
            data.insertComment(threadId = threadId, authorId = "persona-a", body = "reply $i")
        }
        // deliberately NOT calling markRead → all N replies remain unread
    }

    @When("the owner opens the front page")
    fun ownerOpensFrontPage() {
        val resp = http.get("/")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the fresh-forum empty state is shown")
    fun freshForumEmptyStateShown() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-empty-state", "no-threads"),
            "expected data-empty-state=\"no-threads\" in:\n${world.lastBody}",
        )
    }

    @Then("the thread row shows a {string} badge")
    fun threadRowShowsBadge(badge: String) {
        val count = badge.removeSuffix(" new").trim()
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-unread-count", count),
            "expected data-unread-count=\"$count\" in:\n${world.lastBody}",
        )
    }
}
