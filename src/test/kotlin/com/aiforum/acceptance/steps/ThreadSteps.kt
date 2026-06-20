package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for thread-level operations (create, view, empty-state assertion).
 * The When step POSTs to /threads; the Then steps assert against the rendered thread page.
 */
class ThreadSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    @When("the owner creates a thread {string} asking {string} of {string}")
    fun createThread(title: String, text: String, personaList: String) {
        val personaIds = personaList.split(",").map { it.trim() }
        val resp = http.postJson(
            "/threads",
            mapOf("title" to title, "text" to text, "personaIds" to personaIds),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        world.threadId = resp.body?.let {
            Regex("""data-thread-id="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
    }

    @Then("the thread exists with title {string}")
    fun threadExistsWithTitle(title: String) {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(
            Html.contains(world.lastBody ?: "", title),
            "expected title \"$title\" in thread page:\n${world.lastBody}",
        )
    }

    @Then("the thread shows the waiting-on-the-room empty state")
    fun threadShowsWaitingState() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-empty-state", "waiting"),
            "expected data-empty-state=\"waiting\" in:\n${world.lastBody}",
        )
    }
}
