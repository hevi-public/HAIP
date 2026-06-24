package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for the shared header's primary section navigation. The nav links are the only way to
 * reach each section's page, so over HTTP we pin the structural contract — each section link renders with
 * its href and label inside the header — independent of the CSS/JS chrome. (The When steps that load a
 * page are reused from HomeSteps / ThreadSteps.)
 */
class SiteNavSteps(
    private val world: ScenarioWorld,
) {
    @Then("the header nav links {string} to {string}")
    fun headerNavLinks(label: String, path: String) {
        val body = world.lastBody ?: ""
        // <a href="PATH" ...>LABEL — tolerant of intervening attributes (aria-current) and whitespace, so
        // it survives a redesign while still failing if a section link is dropped or re-pointed.
        val link = Regex("<a\\b[^>]*href=\"${Regex.escape(path)}\"[^>]*>\\s*${Regex.escape(label)}\\b")
        assertTrue(
            link.containsMatchIn(body),
            "expected a header nav link \"$label\" -> \"$path\" in:\n$body",
        )
    }
}
