package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.config.PersonaSeedProperties
import com.aiforum.config.PersonaSeeder
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drives the real seeding logic (the un-gated [PersonaSeeder] bean) against the real config
 * ([PersonaSeedProperties], bound from application.yml) and asserts the outcome over HTTP on the real
 * members page. The startup trigger is `@Profile("!test")`, so the scenario invokes seedMissing()
 * directly — there is no HTTP surface for a startup concern — but everything it asserts is full-stack.
 *
 * NOT @Component: glue is instantiated by Cucumber, which injects these Spring beans (see the
 * cucumber-spring-bdd skill).
 */
class PersonaSeedSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val seeder: PersonaSeeder,
    private val props: PersonaSeedProperties,
) {
    @Given("an empty forum")
    fun anEmptyForum() {
        // The DB is wiped before every scenario (DatabaseResetHooks); assert the precondition so a
        // seeding scenario can never silently lean on personas left by something else.
        val body = http.get("/personas").body ?: ""
        props.personas.forEach { p ->
            assertTrue(!Html.hasAttr(body, "data-persona-id", p.id), "forum was not empty: ${p.id} already present")
        }
    }

    @When("the predefined personas are seeded")
    @When("the predefined personas are seeded again")
    @Given("the predefined personas have already been seeded")
    fun seedPredefinedPersonas() {
        world.lastSeedCount = seeder.seedMissing()
    }

    @Then("every predefined persona appears in the members list")
    fun everyPredefinedPersonaAppears() {
        val body = http.get("/personas").body ?: ""
        assertTrue(props.personas.isNotEmpty(), "no predefined personas configured — nothing to assert")
        props.personas.forEach { p ->
            assertTrue(Html.hasAttr(body, "data-persona-id", p.id), "expected ${p.id} in the members list:\n$body")
        }
    }

    @Then("no personas are added the second time")
    fun noPersonasAddedSecondTime() {
        assertEquals(0, world.lastSeedCount, "expected the re-seed to add nothing")
    }

    @Then("every predefined persona appears exactly once in the members list")
    fun everyPredefinedPersonaAppearsOnce() {
        val body = http.get("/personas").body ?: ""
        props.personas.forEach { p ->
            assertEquals(1, Html.countAttr(body, "data-persona-id", p.id), "expected exactly one ${p.id} in:\n$body")
        }
    }
}
