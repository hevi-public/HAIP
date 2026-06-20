package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

class PersonaSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
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
}
