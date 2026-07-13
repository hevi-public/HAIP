package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.repo.PersonaRepository
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Acceptance steps for persona deletion — the mirror of [ThreadDeletionSteps]. Persona routes are
 * slug-based (like /personas/{slug}/edit), so deletion POSTs to /personas/{slug}/delete; the slug is
 * derived from the name the same way the app derives it ([PersonaRepository.slugFor]).
 */
class PersonaDeletionSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    @When("the owner deletes the {string} persona")
    fun deletePersona(name: String) {
        val resp = http.post("/personas/${PersonaRepository.slugFor(name)}/delete")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the delete control is present on the {string} member row")
    fun deleteControlPresent(name: String) {
        val slug = PersonaRepository.slugFor(name)
        val body = world.lastBody ?: ""
        assertNotNull(
            Html.memberRowAttr(body, name, "data-persona-id"),
            "expected the \"$name\" member row in page:\n$body",
        )
        assertTrue(
            body.contains("hx-post=\"/personas/$slug/delete\""),
            "expected delete control (hx-post=/personas/$slug/delete) in page:\n$body",
        )
    }

    @Then("the members list no longer shows the {string} persona")
    fun membersListNoLongerShows(name: String) {
        assertNull(
            Html.memberRowAttr(world.lastBody ?: "", name, "data-persona-id"),
            "expected the \"$name\" persona to be gone from the members list:\n${world.lastBody}",
        )
    }

    @Then("the members list still shows the {string} persona")
    fun membersListStillShows(name: String) {
        assertNotNull(
            Html.memberRowAttr(world.lastBody ?: "", name, "data-persona-id"),
            "expected the \"$name\" persona to still be on the members list:\n${world.lastBody}",
        )
    }
}
