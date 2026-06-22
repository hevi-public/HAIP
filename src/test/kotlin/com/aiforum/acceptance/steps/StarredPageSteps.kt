package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the /starred dedicated page and the "See all" link in the starred rail box.
 */
class StarredPageSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    private fun body(): String = world.lastBody ?: ""

    @When("the owner visits the starred page")
    fun visitStarredPage() {
        val resp = http.get("/starred")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the starred page has an empty state")
    fun starredPageEmptyState() {
        assertTrue(
            Html.hasAttr(body(), "data-starred-empty", "true"),
            "expected data-starred-empty=\"true\" in:\n${body()}",
        )
    }

    @Then("the starred page shows {string}")
    fun starredPageShows(text: String) {
        assertTrue(
            Html.contains(body(), text),
            "expected the starred page to contain \"$text\" in:\n${body()}",
        )
    }

    @Then("the starred page has an entry for {string}'s reply")
    fun starredPageHasEntry(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertTrue(
            Html.hasAttr(body(), "data-starred-item", id),
            "expected data-starred-item=\"$id\" in:\n${body()}",
        )
    }

    @Then("the starred rail links to {string}")
    fun starredRailLinksTo(path: String) {
        assertTrue(
            body().contains("href=\"$path\""),
            "expected a link to \"$path\" in the page in:\n${body()}",
        )
    }
}
