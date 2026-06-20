package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for composer & reply targeting (§4): inline composer targets the clicked node
 * with BRANCH_ONLY scope; the persistent bottom composer targets level 0 with WHOLE_THREAD scope.
 */
class ComposerSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
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
}
