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
                // The composer authors the owner's message: it persists `text` as the owner's node and
                // seeds the room with it (the real fragments/composer.kte carries this same flag).
                "postAsOwner" to "true",
            ),
        )
        world.lastStatus = resp.statusCode.value()
        assertNotNull(resp.body, "expected a rendered fragment from the form submit")
        // Stash the raw swap payload (pre-settle) so a scenario can assert on the structure the browser
        // receives — the owner node with the DRAFTING persona node(s) nested inside it.
        world.lastFragment = resp.body
        // The fragment leads with the owner's posted node, the DRAFTING persona node(s) nested inside.
        // Settle them all so the assertions see the persona reply POSTED (the owner node is already posted).
        val ids = Html.allReplyIds(resp.body ?: "")
        require(ids.isNotEmpty()) { "form submit returned no nodes" }
        world.lastReplyId = ids.last()
        world.lastBody = settle.awaitAllSettled(ids)
    }

    @When("the owner replies inline on {string} with text {string} selecting {string}")
    fun replyInline(targetLabel: String, text: String, persona: String) {
        // The inline composer's path: BRANCH_ONLY, postAsOwner, parented at the clicked node. The owner's
        // message attaches directly under that node — where they clicked Reply — not the branch's tail.
        val parentId = world.replyIds[targetLabel] ?: error("no remembered node $targetLabel")
        val resp = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf(
                "personaIds" to listOf(persona),
                "text" to text,
                "scope" to "BRANCH_ONLY",
                "parentId" to parentId,
                "triggerMode" to "SUMMON",
                "postAsOwner" to true,
            ),
        )
        world.lastFragment = resp.body
        // The returned fragment's root IS the new owner node (the seeded owner nodes aren't in it).
        world.replyIds["new owner message"] =
            Html.replyIdWithAuthor(resp.body ?: "", "owner") ?: error("no owner node in fragment:\n${resp.body}")
        Html.allReplyIds(resp.body ?: "").forEach { settle.awaitSettled(it) }
    }

    @Then("the new owner message is nested under {string}")
    fun newOwnerMessageNestedUnder(label: String) {
        val parentId = world.replyIds[label] ?: error("no remembered node $label")
        val newOwnerId = world.replyIds["new owner message"] ?: error("no new owner message captured")
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(
            Html.isNestedUnder(body, newOwnerId, parentId),
            "expected the new owner message ($newOwnerId) nested under \"$label\" ($parentId) in:\n$body",
        )
    }

    @Then("the returned fragment nests {string}'s draft under the owner's message")
    fun fragmentNestsDraftUnderOwner(persona: String) {
        // Assert on the raw htmx-swap payload (what the browser appends), NOT a re-fetched page: the
        // owner node and the persona draft must be nested here too, or the live thread flattens until a
        // refresh re-runs the server-side tree assembly.
        val fragment = world.lastFragment ?: error("no /generate fragment captured")
        val ownerId = Html.replyIdWithAuthor(fragment, "owner") ?: error("no owner node in the fragment:\n$fragment")
        val draftId = Html.replyIdWithAuthor(fragment, persona) ?: error("no $persona draft in the fragment:\n$fragment")
        assertTrue(
            Html.isNestedUnder(fragment, draftId, ownerId),
            "expected $persona's draft ($draftId) nested under the owner's node ($ownerId) in the swap payload:\n$fragment",
        )
    }

    @Then("{string}'s reply renders nested under the owner's message")
    fun replyNestsUnderOwner(persona: String) {
        // Re-fetch the rendered thread tree and assert genuine DOM containment: the persona's <article>
        // must sit INSIDE the owner's <article>. The flat-rendering bug put both at level 0 (siblings),
        // which this fails — proving the test exercises the nesting, not just the nodes' presence.
        val body = http.get("/threads/${world.threadId}").body ?: ""
        val ownerId = Html.replyIdWithAuthor(body, "owner") ?: error("no owner node on the thread page:\n$body")
        val replyId = Html.replyIdWithAuthor(body, persona) ?: error("no $persona reply on the thread page:\n$body")
        assertTrue(
            Html.isNestedUnder(body, replyId, ownerId),
            "expected $persona's reply ($replyId) nested under the owner's node ($ownerId) in:\n$body",
        )
    }

    @Then("the thread shows the owner's post {string}")
    fun threadShowsOwnerPost(text: String) {
        // Re-fetch the thread page: the owner's composed text must render as their OWN node (data-author
        //="owner"), not just feed the personas — otherwise the owner's words never appear in the tree.
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(Html.hasAttr(body, "data-author", "owner"), "expected an owner-authored node in:\n$body")
        assertTrue(Html.contains(body, text), "expected the owner's text \"$text\" in the thread:\n$body")
    }
}
