package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import com.aiforum.llm.LlmRequest
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

class PersonaSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
) {
    // The composition call rides the single LlmClient seam tagged with this synthetic persona, so the
    // spy can tell a prompt-authoring call apart from a normal generation call (see PromptComposer).
    private val COMPOSER_NAME = "PromptComposer"

    private fun composerCalls(): List<LlmRequest> = llm.received.filter { it.persona.name == COMPOSER_NAME }

    /** Everything the composer was handed: its system role plus the spec/instruction turn(s). */
    private fun LlmRequest.allText(): String =
        context.personaSystemPrompt + " " + context.comments.joinToString(" ") { it.body }
    @When("the owner opens the profile for {string}")
    fun openProfile(name: String) {
        val resp = http.get("/personas/$name")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the profile shows the persona's name and descriptor")
    fun profileShowsNameAndDescriptor() {
        val body = world.lastBody ?: ""
        assertTrue(
            Regex("""data-persona-name="[^"]+"""").containsMatchIn(body),
            "expected data-persona-name attribute in:\n$body",
        )
        assertTrue(
            Regex("""data-persona-descriptor="[^"]+"""").containsMatchIn(body),
            "expected data-persona-descriptor attribute in:\n$body",
        )
    }

    @When("the owner adds a persona {string} described as {string}")
    fun addPersona(name: String, descriptor: String) {
        val resp = http.postForm("/personas", mapOf("name" to name, "descriptor" to descriptor))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner opens the members list")
    fun openMembersList() {
        val resp = http.get("/personas")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the members page offers a name and descriptor field")
    fun membersPageOffersCreateForm() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "name=\"name\""), "expected a name=\"name\" field in:\n$body")
        assertTrue(Html.contains(body, "name=\"descriptor\""), "expected a name=\"descriptor\" field in:\n$body")
    }

    @Then("the persona {string} exists")
    fun personaExists(name: String) {
        val resp = http.get("/personas/$name")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(resp.statusCode.is2xxSuccessful, "expected 200 for /personas/$name, got ${resp.statusCode}")
    }

    @Then("{string} appears in the members list")
    fun appearsInMembersList(name: String) {
        val resp = http.get("/personas")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(
            Html.hasAttr(resp.body ?: "", "data-persona-id", name),
            "expected data-persona-id=\"$name\" in:\n${resp.body}",
        )
    }

    @When("the owner adds a persona {string} with abilities {string} and dials agreeableness {int}, verbosity {int}")
    fun addPersonaWithTraits(name: String, abilities: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas",
            mapOf(
                "name" to name,
                "abilities" to abilities,
                "dial_agreeableness" to agreeableness,
                "dial_verbosity" to verbosity,
            ),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Given("a persona {string} exists with system prompt {string} and dials agreeableness {int}, verbosity {int}")
    fun seedPersonaWithPromptAndDials(name: String, systemPrompt: String, agreeableness: Int, verbosity: Int) {
        data.insertPersona(
            id = name,
            name = name,
            systemPrompt = systemPrompt,
            dials = mapOf("agreeableness" to agreeableness, "verbosity" to verbosity),
        )
    }

    @When("the owner edits {string} setting dials agreeableness {int}, verbosity {int}")
    fun editPersonaDials(name: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas/$name/edit",
            mapOf("dial_agreeableness" to agreeableness, "dial_verbosity" to verbosity),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the persona {string} has abilities {string}")
    fun personaHasAbilities(name: String, abilities: String) {
        val body = http.get("/personas/$name").body ?: ""
        assertTrue(
            Html.hasAttr(body, "data-persona-abilities", abilities),
            "expected data-persona-abilities=\"$abilities\" in:\n$body",
        )
    }

    @Then("the persona {string} has system prompt {string}")
    fun personaHasSystemPrompt(name: String, prompt: String) {
        val body = http.get("/personas/$name").body ?: ""
        assertTrue(
            Html.hasAttr(body, "data-system-prompt", prompt),
            "expected data-system-prompt=\"$prompt\" in:\n$body",
        )
    }

    @Then("the composer was asked to honour the dials")
    fun composerHonouredDials() {
        val calls = composerCalls()
        assertTrue(calls.isNotEmpty(), "expected a composition call to the LLM, got: ${llm.received.map { it.persona.name }}")
        assertTrue(
            calls.any { it.allText().contains("agreeableness", ignoreCase = true) },
            "expected the composer instruction to mention the dials, got:\n${calls.joinToString("\n") { it.allText() }}",
        )
    }

    @Then("the composer was given the previous prompt {string}")
    fun composerGivenPreviousPrompt(previous: String) {
        val calls = composerCalls()
        assertTrue(calls.isNotEmpty(), "expected a composition call to the LLM, got: ${llm.received.map { it.persona.name }}")
        assertTrue(
            calls.any { it.allText().contains(previous) },
            "expected the composer to be handed the previous prompt \"$previous\", got:\n${calls.joinToString("\n") { it.allText() }}",
        )
    }

    @Then("the members page offers an abilities field and dial controls")
    fun membersPageOffersTraitFields() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "name=\"abilities\""), "expected a name=\"abilities\" field in:\n$body")
        assertTrue(Html.contains(body, "name=\"dial_agreeableness\""), "expected a name=\"dial_agreeableness\" control in:\n$body")
    }
}
