package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the front-page side rails (the design's left/right rail boxes). Asserts on the stable
 * data-* hooks each box emits: data-rail-box="<name>" marks the box, data-<entry> carries each row's
 * id/key, and data-<name>-empty marks the empty state. Reuses the front-page fetch in HomeSteps
 * ("the owner opens the front page").
 */
class HomeRailSteps(
    private val world: ScenarioWorld,
) {
    private fun body(): String = world.lastBody ?: ""

    // Each rail's row hook and empty-state hook, keyed by the data-rail-box name used in the feature.
    private val entryAttr = mapOf(
        "members" to "data-member-entry",
        "active-threads" to "data-active-thread",
        "recent-comments" to "data-recent-comment",
        "forum-nav" to "data-nav-entry",
    )
    private val emptyAttr = mapOf(
        "members" to "data-members-empty",
        "active-threads" to "data-active-threads-empty",
        "recent-comments" to "data-recent-comments-empty",
    )

    private fun entries(rail: String): List<String> =
        Html.attrValues(body(), entryAttr[rail] ?: error("unknown rail \"$rail\""))

    @Then("the front page shows the {string} rail box")
    fun frontPageShowsRailBox(rail: String) {
        assertTrue(
            Html.hasAttr(body(), "data-rail-box", rail),
            "expected a \"$rail\" rail box (data-rail-box=\"$rail\") in:\n${body()}",
        )
    }

    @Then("the {string} rail lists {int} entries")
    fun railListsEntries(rail: String, count: Int) {
        assertEquals(count, entries(rail).size, "$rail rail entries in:\n${body()}")
    }

    @Then("the {string} rail has an entry for {string}")
    fun railHasEntryFor(rail: String, key: String) {
        assertTrue(
            entries(rail).contains(key),
            "expected the $rail rail to have an entry for \"$key\"; entries were ${entries(rail)} in:\n${body()}",
        )
    }

    @Then("the {string} rail shows {string}")
    fun railShows(rail: String, text: String) {
        // The home page renders comment bodies only inside the recent-comments box, so a page-level
        // contains is a safe proxy for "this box shows the text".
        assertTrue(Html.contains(body(), text), "expected the $rail rail to show \"$text\" in:\n${body()}")
    }

    @Then("the {string} rail shows an empty state")
    fun railShowsEmptyState(rail: String) {
        val attr = emptyAttr[rail] ?: error("no empty-state hook for rail \"$rail\"")
        assertTrue(
            Html.hasAttr(body(), attr, "true"),
            "expected the $rail rail empty state ($attr=\"true\") in:\n${body()}",
        )
    }
}
