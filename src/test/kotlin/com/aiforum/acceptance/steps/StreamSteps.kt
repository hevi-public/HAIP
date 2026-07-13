package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.agui.AguiEvent
import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.service.InFlightGenerations
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drives the SSE event stream (`GET /replies/{id}/stream`) over HTTP. The PRODUCTION pipe
 * (settleOne → channel publish, plus the per-backend NDJSON/SSE normalisation) is proven in the tier-1/2
 * tests; here we verify the TRANSPORT end-to-end — that a run's buffered AG-UI events are replayed to a
 * subscriber as real SSE frames, and that an unknown run completes at once (the poll-fallback contract).
 * We populate the in-flight channel directly (a terminal buffer) so the streamed response is deterministic
 * without racing a live generation.
 */
class StreamSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val inFlight: InFlightGenerations,
) {

    @Given("a streaming generation {string} has produced {string} then {string}")
    fun produced(id: String, first: String, second: String) {
        val draft = Comment(id, "t", null, "p", "", GenerationState.DRAFTING, null, 0).toReplyView()
        inFlight.register(id, "t", draft)
        inFlight.publish(id, AguiEvent.RunStarted(id))
        inFlight.publish(id, AguiEvent.TextDelta(id, first))
        inFlight.publish(id, AguiEvent.TextDelta(id, second))
        inFlight.publish(id, AguiEvent.RunFinished(id))
    }

    @When("the owner opens the event stream for {string}")
    fun openStream(id: String) {
        val resp = http.get("/replies/$id/stream")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the event stream carries an AG-UI {string} event")
    fun carriesEvent(type: String) {
        assertEquals(200, world.lastStatus)
        assertTrue(world.lastBody!!.contains(type)) { "stream missing $type:\n${world.lastBody}" }
    }

    @Then("the event stream carries the text deltas {string} and {string}")
    fun carriesDeltas(first: String, second: String) {
        val body = world.lastBody.orEmpty()
        assertTrue(body.contains("TEXT_MESSAGE_CONTENT")) { "no content events:\n$body" }
        assertTrue(body.contains(first) && body.contains(second)) { "missing deltas '$first'/'$second':\n$body" }
    }

    @Then("the event stream is empty")
    fun streamEmpty() {
        assertEquals(200, world.lastStatus)
        assertTrue(world.lastBody.isNullOrBlank()) { "expected an empty stream, got:\n${world.lastBody}" }
    }
}
