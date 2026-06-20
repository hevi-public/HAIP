package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for composer & reply targeting (§4): inline composer targets the clicked node
 * with BRANCH_ONLY scope; the persistent bottom composer targets level 0 with WHOLE_THREAD scope.
 */
class ComposerSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @When("the owner opens the inline composer on {string}'s reply")
    fun openInlineComposer(persona: String) {
        world.composerTargetId = world.replyIds["$persona's reply"]
            ?: error("no remembered reply for $persona")
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner uses the bottom composer")
    fun useBottomComposer() {
        world.composerTargetId = world.threadId ?: error("no thread in world")
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the composer targets that node")
    fun composerTargetsThatNode() {
        val targetId = world.composerTargetId ?: error("no composer target remembered")
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-target-id", targetId),
            "expected data-target-id=\"$targetId\" in:\n${world.lastBody}",
        )
    }

    @Then("the reply targets the post at level 0")
    fun replyTargetsLevelZero() {
        val threadId = world.threadId ?: error("no thread in world")
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-target-id", threadId),
            "expected bottom composer data-target-id=\"$threadId\" in:\n${world.lastBody}",
        )
    }

    @Then("the composer scope defaults to {string}")
    fun composerScopeDefaults(scope: String) {
        val targetId = world.composerTargetId ?: error("no composer target remembered")
        assertEquals(
            scope,
            Html.composerScope(world.lastBody ?: "", targetId),
            "expected composer targeting $targetId to have data-scope=\"$scope\"",
        )
    }

    @Then("the composer posts to the generation endpoint")
    fun composerPostsToGenerationEndpoint() {
        val targetId = world.composerTargetId ?: error("no composer target remembered")
        assertEquals(
            "/threads/${world.threadId}/generate",
            Html.composerAttr(world.lastBody ?: "", targetId, "hx-post"),
            "expected composer targeting $targetId to hx-post the generation endpoint",
        )
    }

    @Then("the composer offers a text field and a persona picker")
    fun composerOffersFields() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "name=\"text\""), "expected a name=\"text\" field in:\n$body")
        assertTrue(Html.contains(body, "name=\"personaIds\""), "expected a name=\"personaIds\" picker in:\n$body")
    }

    @Then("the composer summons on submit")
    fun composerSummonsOnSubmit() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "value=\"SUMMON\""), "expected a SUMMON trigger-mode field in:\n$body")
    }

    @When("the owner submits the bottom composer with text {string} selecting {string}")
    fun submitBottomComposer(text: String, persona: String) {
        // The browser's path: an htmx form POST (application/x-www-form-urlencoded), not the JSON the
        // API/acceptance summon uses. Same endpoint, same returned reply-node fragment.
        val resp = http.postForm(
            "/threads/${world.threadId}/generate",
            mapOf(
                "personaIds" to persona,
                "text" to text,
                "scope" to "WHOLE_THREAD",
                "triggerMode" to "SUMMON",
            ),
        )
        world.lastStatus = resp.statusCode.value()
        assertNotNull(resp.body, "expected a rendered fragment from the form submit")
        // Async: the form POST returns a DRAFTING node; settle it so the assertions see POSTED.
        val id = Html.allReplyIds(resp.body ?: "").firstOrNull() ?: error("form submit returned no draft node")
        world.lastReplyId = id
        world.lastBody = settle.awaitSettled(id)
    }
}
