package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Thread-deletion steps (§8): the front-page bin control and the cascade endpoint. Asserts on the
 * stable data-thread-* hooks and the hx-post wiring, never CSS classes (see the cucumber-spring-bdd
 * skill). "the owner opens the front page" lives in HomeSteps and is reused here.
 */
class ThreadDeletionSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    @When("the owner deletes the {string} thread")
    fun deleteThread(title: String) {
        val id = world.threadIds[title] ?: error("no seeded thread titled \"$title\"")
        val resp = http.post("/threads/$id/delete")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the delete control is present on the {string} row")
    fun deleteControlPresent(title: String) {
        val id = world.threadIds[title] ?: error("no seeded thread titled \"$title\"")
        val body = world.lastBody ?: ""
        assertNotNull(
            Html.threadRowAttr(body, title, "data-thread-id"),
            "expected the \"$title\" thread row in page:\n$body",
        )
        assertTrue(
            body.contains("hx-post=\"/threads/$id/delete\""),
            "expected delete control (hx-post=/threads/$id/delete) in page:\n$body",
        )
    }

    @Then("the front page no longer shows the {string} thread")
    fun frontPageNoLongerShows(title: String) {
        assertNull(
            Html.threadRowAttr(world.lastBody ?: "", title, "data-thread-id"),
            "expected the \"$title\" thread to be gone from the front page:\n${world.lastBody}",
        )
    }

    @Then("the front page still shows the {string} thread")
    fun frontPageStillShows(title: String) {
        assertNotNull(
            Html.threadRowAttr(world.lastBody ?: "", title, "data-thread-id"),
            "expected the \"$title\" thread to still be on the front page:\n${world.lastBody}",
        )
    }
}
