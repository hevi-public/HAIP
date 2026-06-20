package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Trigger modes (§4): sequential fan-out / roomful in M1, where one persona failing does NOT abort the
 * room (partial-roomful). The LLM behaviours are enqueued in persona order by preceding steps; a single
 * worker settles the personas in that order, preserving the deque-scripted mapping.
 */
class TriggerModeSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @When("the owner fans out to {string}")
    fun fanOut(personasCsv: String) {
        val personaIds = personasCsv.split(",").map { it.trim() }
        val resp = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf("personaIds" to personaIds, "text" to "what do you think?", "triggerMode" to "FANOUT"),
        )
        world.lastStatus = resp.statusCode.value()
        // Async: the POST returns N DRAFTING nodes; settle them all, then reassemble the room so the
        // posted/failed counts below see every node.
        val ids = Html.allReplyIds(resp.body ?: "")
        world.lastBody = settle.awaitAllSettled(ids)
    }

    @Then("exactly {int} replies are posted")
    fun postedCount(count: Int) =
        assertEquals(count, Html.countAttr(world.lastBody ?: "", "data-state", "posted"))

    @Then("exactly {int} reply is failed")
    fun failedCount(count: Int) =
        assertEquals(count, Html.countAttr(world.lastBody ?: "", "data-state", "failed"))
}
