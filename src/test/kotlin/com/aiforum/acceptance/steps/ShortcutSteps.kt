package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableShortcutClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.shortcut.StoryCard
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the Shortcut surfaces. Programs the scriptable [ScriptableShortcutClient] (no real IO) and
 * asserts on the stable data-* hooks the JTE templates emit — data-rail-box="shortcut", data-story-id /
 * data-story-state on a card, data-shortcut-source/-empty/-error/-disabled on the page — plus the inline
 * sc-N link host. Reuses the existing front-page / thread navigation steps (HomeSteps, ThreadSteps).
 */
class ShortcutSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val shortcut: ScriptableShortcutClient,
) {
    private fun body(): String = world.lastBody ?: ""

    @Given("the Shortcut integration is active")
    fun integrationActive() {
        shortcut.active = true
    }

    @Given("the Shortcut integration is off")
    fun integrationOff() {
        shortcut.active = false
    }

    @Given("Shortcut has the story sc-{int} {string} of type {string} in state {string}")
    fun shortcutHasStory(id: Int, name: String, type: String, state: String) {
        val publicId = id.toLong()
        // Each story gets a unique workflow-state id; registering its name drives the real state-resolution
        // path in ShortcutService (search returns the id; workflows resolve the name).
        shortcut.states = shortcut.states + (publicId to state)
        shortcut.add(
            StoryCard(
                publicId = publicId,
                name = name,
                type = type,
                stateId = publicId,
                state = "",
                url = "https://app.shortcut.com/test-workspace/story/$publicId",
            ),
        )
    }

    @Given("the next Shortcut call fails")
    fun nextCallFails() {
        shortcut.failNext = true
    }

    @When("the owner opens the Shortcut page")
    fun openShortcutPage() {
        world.lastBody = http.get("/shortcut").body
    }

    @When("the owner opens the Shortcut page source {string}")
    fun openShortcutPageSource(source: String) {
        world.lastBody = http.get("/shortcut?source=$source").body
    }

    @Then("the page does not show the {string} rail box")
    fun pageDoesNotShowRailBox(rail: String) {
        assertFalse(
            Html.hasAttr(body(), "data-rail-box", rail),
            "expected NO \"$rail\" rail box, but found one in:\n${body()}",
        )
    }

    @Then("the Shortcut box lists the story {string}")
    fun boxListsStory(ref: String) = assertStoryListed(ref)

    @Then("the Shortcut page lists the story {string}")
    fun pageListsStory(ref: String) = assertStoryListed(ref)

    private fun assertStoryListed(ref: String) {
        val id = ref.removePrefix("sc-")
        assertTrue(
            Html.hasAttr(body(), "data-story-id", id),
            "expected a story card for \"$ref\" (data-story-id=\"$id\") in:\n${body()}",
        )
    }

    @Then("the Shortcut story {string} shows the state {string}")
    fun storyShowsState(ref: String, state: String) {
        assertTrue(
            Html.hasAttr(body(), "data-story-state", state),
            "expected story \"$ref\" to show state \"$state\" (data-story-state) in:\n${body()}",
        )
    }

    @Then("the Shortcut box shows an empty state")
    fun boxShowsEmptyState() {
        assertTrue(
            Html.hasAttr(body(), "data-shortcut-empty", "true"),
            "expected the Shortcut empty state (data-shortcut-empty=\"true\") in:\n${body()}",
        )
    }

    @Then("the Shortcut page offers the {string} source")
    fun pageOffersSource(source: String) {
        assertTrue(
            Html.hasAttr(body(), "data-shortcut-source", source),
            "expected the \"$source\" source link (data-shortcut-source=\"$source\") in:\n${body()}",
        )
    }

    @Then("Shortcut was queried with {string}")
    fun shortcutQueriedWith(query: String) {
        assertTrue(
            shortcut.received.contains(query),
            "expected a Shortcut search for \"$query\"; queries were ${shortcut.received}",
        )
    }

    @Then("the Shortcut page shows a Shortcut error")
    fun pageShowsError() {
        assertTrue(
            Html.hasAttr(body(), "data-shortcut-error", "true"),
            "expected a Shortcut error note (data-shortcut-error=\"true\") in:\n${body()}",
        )
    }

    @Then("the Shortcut page shows the integration-disabled note")
    fun pageShowsDisabled() {
        assertTrue(
            Html.hasAttr(body(), "data-shortcut-disabled", "true"),
            "expected the disabled note (data-shortcut-disabled=\"true\") in:\n${body()}",
        )
    }

    @Then("the opening post links sc-{int} to Shortcut")
    fun openingPostLinksStory(id: Int) {
        val href = "https://app.shortcut.com/test-workspace/story/$id"
        assertTrue(
            Html.contains(body(), "href=\"$href\""),
            "expected an inline link to $href for sc-$id in:\n${body()}",
        )
    }
}
