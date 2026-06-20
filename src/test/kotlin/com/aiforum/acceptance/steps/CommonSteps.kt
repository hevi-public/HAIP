package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.FailingRepositoryToggle
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableLlmClient.Behavior
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared Given (seed preconditions into the real test DB) and generic Then (assert on the data-*
 * hooks of the last response). Step classes inject Spring beans by constructor (cucumber-spring).
 */
class CommonSteps(
    private val world: ScenarioWorld,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
    private val http: HttpClient,
    private val failingRepo: FailingRepositoryToggle,
) {
    @Given("a thread {string} exists")
    fun threadExists(title: String) {
        world.threadId = data.insertThread(title)
    }

    @Given("a persona {string} exists")
    fun personaExists(name: String) {
        // use the name as the id to keep summon payloads readable in features
        data.insertPersona(id = name, name = name)
    }

    @Given("a posted reply from {string} saying {string}")
    fun postedReply(persona: String, body: String) {
        val id = data.insertComment(world.threadId!!, authorId = persona, body = body)
        world.rememberReply("$persona's reply", id)
    }

    @Given("the LLM will respond with {string}")
    fun llmWillRespond(text: String) = llm.enqueue(Behavior.Respond(text))

    @Given("the next save will fail")
    fun nextSaveWillFail() {
        failingRepo.failNextWrite = true
    }

    @Then("the reply is {string}")
    fun replyIs(state: String) {
        assertTrue(
            Html.hasAttr(body(), "data-state", state.lowercase()),
            "expected a reply with data-state=\"$state\" in:\n${body()}",
        )
    }

    @Then("the reply is not {string}")
    fun replyIsNot(state: String) {
        assertFalse(
            Html.hasAttr(body(), "data-state", state.lowercase()),
            "expected NO reply with data-state=\"$state\" in:\n${body()}",
        )
    }

    @Then("the reply body contains {string}")
    fun replyBodyContains(text: String) {
        assertTrue(Html.contains(body(), text), "expected body to contain \"$text\" in:\n${body()}")
    }

    @Then("the reply failureCategory is {string}")
    fun replyFailureCategory(category: String) {
        assertTrue(
            Html.hasAttr(body(), "data-failure-category", category),
            "expected data-failure-category=\"$category\" in:\n${body()}",
        )
    }

    @Then("the reply retryable is {string}")
    fun replyRetryable(retryable: String) {
        assertTrue(
            Html.hasAttr(body(), "data-retryable", retryable),
            "expected data-retryable=\"$retryable\" in:\n${body()}",
        )
    }

    @Then("the response status is {int}")
    fun responseStatus(status: Int) = assertEquals(status, world.lastStatus)

    private fun body(): String = world.lastBody ?: ""
}
