package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Edit-mode steps (§7): revising a posted comment, an AI persona's reply, and the opening post. Asserts
 * on the stable data-* hooks (data-edited, data-op-body) and the hx-post wiring, never CSS classes.
 */
class EditSteps(
    private val world: ScenarioWorld,
    private val data: TestData,
    private val http: HttpClient,
) {
    @Given("a drafting reply from {string} saying {string}")
    fun draftingReply(persona: String, body: String) {
        val id = data.insertComment(world.threadId!!, authorId = persona, body = body, state = "DRAFTING")
        world.rememberReply("$persona's reply", id)
    }

    @When("the owner edits {string}'s reply to say {string}")
    fun editReply(persona: String, text: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.postForm("/replies/$id/edit", mapOf("text" to text))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner edits the opening post to title {string} and body {string}")
    fun editOpeningPost(title: String, body: String) {
        val resp = http.postForm("/threads/${world.threadId}/edit", mapOf("title" to title, "text" to body))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the edit button is present on {string}'s reply")
    fun editButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/edit\""),
            "expected an edit form (hx-post=/replies/$id/edit) in page:\n${world.lastBody}",
        )
    }

    @Then("the edit button is not present on {string}'s reply")
    fun editButtonAbsent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertFalse(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/edit\""),
            "expected NO edit form (hx-post=/replies/$id/edit) on a non-posted node:\n${world.lastBody}",
        )
    }

    @Then("{string}'s reply body shows {string}")
    fun replyBodyShows(persona: String, text: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertTrue(
            Html.contains(world.lastBody ?: "", text),
            "expected $persona's reply ($id) body to show \"$text\" in:\n${world.lastBody}",
        )
    }

    @Then("{string}'s reply is marked edited")
    fun replyMarkedEdited(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertEquals(
            "true", Html.replyAttr(world.lastBody ?: "", id, "data-edited"),
            "expected data-edited=\"true\" on $persona's reply ($id):\n${world.lastBody}",
        )
    }

    @Then("{string}'s reply is not marked edited")
    fun replyNotMarkedEdited(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertEquals(
            "false", Html.replyAttr(world.lastBody ?: "", id, "data-edited"),
            "expected data-edited=\"false\" on $persona's reply ($id):\n${world.lastBody}",
        )
    }

    @Then("the thread page shows {string}")
    fun threadPageShows(text: String) {
        assertTrue(
            Html.contains(world.lastBody ?: "", text),
            "expected the thread page to show \"$text\" in:\n${world.lastBody}",
        )
    }

    @Then("the opening post body shows {string}")
    fun openingPostBodyShows(text: String) {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "data-op-body"), "expected an opening-post body (data-op-body) in:\n$body")
        assertTrue(Html.contains(body, text), "expected the opening post to show \"$text\" in:\n$body")
    }

    @Then("the opening post is marked edited")
    fun openingPostMarkedEdited() {
        // The OP block is the only element carrying data-edited="true" on a thread with no edited replies.
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-edited", "true"),
            "expected the opening post to carry data-edited=\"true\" in:\n${world.lastBody}",
        )
    }
}
