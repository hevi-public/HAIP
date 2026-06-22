package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regenerate steps (§7): re-running a persona reply while keeping every prior take. Regenerate is
 * synchronous (no draft polling — like Retry), so the POST returns the re-rendered node directly. Asserts
 * on the stable data-* hooks (data-regeneratable, data-revision-index/count) and the hx-post wiring, never
 * CSS classes. Reuses the body/nesting Then steps from EditSteps/OwnerControlSteps.
 */
class RegenerationSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    private fun replyId(persona: String) =
        world.replyIds["$persona's reply"] ?: error("no remembered reply for \"$persona's reply\"")

    @When("the owner regenerates {string}'s reply")
    fun regenerate(persona: String) {
        val resp = http.post("/replies/${replyId(persona)}/regenerate")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner switches {string}'s reply to version {int}")
    fun switchVersion(persona: String, version: Int) {
        // The feature names 1-based versions; the endpoint takes the 0-based stored index.
        val resp = http.post("/replies/${replyId(persona)}/revision/${version - 1}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the regenerate button is present on {string}'s reply")
    fun regenerateButtonPresent(persona: String) {
        val id = replyId(persona)
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/regenerate\""),
            "expected a regenerate control (hx-post=/replies/$id/regenerate) in page:\n${world.lastBody}",
        )
    }

    @Then("the regenerate button is not present on {string}'s reply")
    fun regenerateButtonAbsent(persona: String) {
        val id = replyId(persona)
        assertFalse(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/regenerate\""),
            "expected NO regenerate control on a non-persona reply:\n${world.lastBody}",
        )
    }

    @Then("{string}'s reply shows version {int} of {int}")
    fun replyShowsVersion(persona: String, index: Int, count: Int) {
        val id = replyId(persona)
        val body = world.lastBody ?: ""
        assertEquals(
            index.toString(), Html.replyAttr(body, id, "data-revision-index"),
            "expected $persona's reply ($id) at version $index in:\n$body",
        )
        assertEquals(
            count.toString(), Html.replyAttr(body, id, "data-revision-count"),
            "expected $persona's reply ($id) to have $count versions in:\n$body",
        )
    }

    @Then("{string}'s reply has no version indicator")
    fun replyHasNoVersionIndicator(persona: String) {
        val id = replyId(persona)
        assertEquals(
            "1", Html.replyAttr(world.lastBody ?: "", id, "data-revision-count"),
            "expected $persona's reply ($id) to be a single take (data-revision-count=\"1\"):\n${world.lastBody}",
        )
    }
}
