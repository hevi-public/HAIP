package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The context differentiator (§5): branch-only scope must hand the model only the ancestor path, not
 * siblings. Asserted by spying on the PromptContext. Nodes are seeded with body == label so the
 * assertions read naturally.
 */
class ContextScopingSteps(
    private val world: ScenarioWorld,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @Given("a root comment {string} by {string}")
    fun rootComment(label: String, author: String) {
        world.replyIds[label] = data.insertComment(world.threadId!!, authorId = author, body = label, parentId = null, depth = 0)
    }

    @Given("a reply {string} under {string} by {string}")
    fun replyUnder(label: String, parentLabel: String, author: String) {
        val parentId = world.replyIds[parentLabel] ?: error("no node $parentLabel")
        world.replyIds[label] = data.insertComment(world.threadId!!, authorId = author, body = label, parentId = parentId)
    }

    @When("the owner replies under {string} with {word} scope")
    fun replyWithScope(parentLabel: String, scope: String) {
        postReply(parentLabel, scope.uppercase().replace('-', '_'), includeSiblings = false)
    }

    @When("the owner replies under {string} with branch-only scope including siblings")
    fun replyBranchWithSiblings(parentLabel: String) {
        postReply(parentLabel, "BRANCH_ONLY", includeSiblings = true)
    }

    private fun postReply(parentLabel: String, scope: String, includeSiblings: Boolean) {
        val parentId = world.replyIds[parentLabel] ?: error("no node $parentLabel")
        val resp = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf(
                "personaIds" to listOf("sol"),
                "text" to "what do you think?",
                "scope" to scope,
                "includeSiblings" to includeSiblings,
                "parentId" to parentId,
                "triggerMode" to "SUMMON",
            ),
        )
        // Async: wait for the worker to call the seam (and settle) so the spy assertions below see the
        // exact PromptContext it was handed.
        Html.allReplyIds(resp.body ?: "").firstOrNull()?.let { settle.awaitSettled(it) }
    }

    @Then("the model context includes node {string}")
    fun contextIncludes(label: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(req.context.comments.any { it.body == label }, "expected node \"$label\" in context")
    }

    // Substring match, for the opening post: its context node carries title + body joined as one post, so
    // an exact-body assertion doesn't fit. Used to prove BOTH the topic and its detail reach the room.
    @Then("the model context mentions {string}")
    fun contextMentions(text: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(req.context.comments.any { it.body.contains(text) }, "expected context to mention \"$text\"")
    }

    @Then("the model context excludes node {string}")
    fun contextExcludes(label: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(req.context.comments.none { it.body == label }, "node \"$label\" should NOT be in context")
    }

    // The reply-target marker (§5): in whole-thread scope the target is rarely the last transcript line, so
    // the model must be told WHICH node it answers. Asserted on the spied PromptContext.targetId.
    @Then("the model is told to reply to node {string}")
    fun targetIsNode(label: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        val target = req.context.comments.firstOrNull { it.id == req.context.targetId }
            ?: error("no target node in context (targetId=${req.context.targetId})")
        assertTrue(target.body == label, "expected reply target \"$label\" but was \"${target.body}\"")
    }
}
