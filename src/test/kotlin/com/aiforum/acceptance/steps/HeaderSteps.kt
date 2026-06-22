package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for the shared site header. The sticky/scroll-to-top behaviour is CSS + JS and lives
 * in the browser; over HTTP we can only pin the structural contract — that the <header> renders the
 * data-scroll-top hook header.js binds to (the When steps are reused from HomeSteps / ThreadSteps).
 */
class HeaderSteps(
    private val world: ScenarioWorld,
) {
    @Then("the page header is a scroll-to-top anchor")
    fun headerIsScrollToTopAnchor() {
        val body = world.lastBody ?: ""
        assertTrue(
            Regex("<header\\b[^>]*\\bdata-scroll-top\\b").containsMatchIn(body),
            "expected a <header> carrying the data-scroll-top hook in:\n$body",
        )
    }
}
