package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the Admin → Statistics page (/admin/stats). Asserts the page reflects the routing_event rows
 * that the real "ask the room" path recorded earlier in the scenario — so this exercises the whole
 * pipeline (router classifies → repository persists → page renders), not a seeded shortcut.
 */
class RoutingStatsSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    private fun body(): String = world.lastBody ?: ""

    @When("the owner visits the routing stats page")
    fun visitStatsPage() {
        val resp = http.get("/admin/stats")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the routing stats page has no parse-miss rate yet")
    fun noParseMissRate() {
        assertTrue(
            Html.hasAttr(body(), "data-parse-miss-rate-empty", "true"),
            "expected the empty-rate state (no routing decisions yet) in:\n${body()}",
        )
    }

    @Then("the routing stats page counts {int} {string} event")
    fun countsEvent(count: Int, outcome: String) {
        assertTrue(
            Html.hasAttr(body(), "data-count", "$outcome=$count"),
            "expected $count \"$outcome\" events (data-count=\"$outcome=$count\") in:\n${body()}",
        )
    }

    @Then("the routing stats page shows a parse-miss rate of {string}")
    fun parseMissRate(rate: String) {
        assertTrue(
            Html.hasAttr(body(), "data-parse-miss-rate", rate),
            "expected a parse-miss rate of \"$rate\" in:\n${body()}",
        )
    }
}
