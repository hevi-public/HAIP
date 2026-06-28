package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import tools.jackson.databind.ObjectMapper

/**
 * Steps for the quote graph's forward direction (plan_docs/comment-quotes.md). The browser selection +
 * context menu can't be driven over HTTP (verified in the preview, like keyboard nav), so these drive
 * the SERVER contract: the `quotesJson` the composer sends becomes a persisted quote edge that renders
 * as a forward "↗ author" anchor (data-quote-source) on the quoting reply after a thread re-render.
 */
class QuoteSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val objectMapper: ObjectMapper,
) {
    private fun body(): String = world.lastBody ?: ""
    private fun replyId(alias: String): String =
        world.replyIds["$alias's reply"] ?: error("no remembered reply for \"$alias's reply\"")

    // Post the owner's reply via /note (a silent owner comment — no LLM summon), carrying [quotes] as the
    // composer's quotesJson, then remember the new node as "owner's reply".
    private fun postQuoting(text: String, quotes: List<Map<String, String>>) {
        val form = mutableMapOf<String, Any?>("text" to text)
        if (quotes.isNotEmpty()) form["quotesJson"] = objectMapper.writeValueAsString(quotes)
        val res = http.postForm("/threads/${world.threadId}/note", form)
        world.lastStatus = res.statusCode.value()
        world.lastBody = res.body
        Html.allReplyIds(res.body ?: "").firstOrNull()?.let { world.rememberReply("owner's reply", it) }
    }

    private fun quote(targetAlias: String, text: String): Map<String, String> =
        mapOf("targetId" to replyId(targetAlias), "text" to text)

    @When("the owner posts a reply {string} quoting {string} from {string}'s reply")
    fun postsQuoting(text: String, quoted: String, target: String) =
        postQuoting(text, listOf(quote(target, quoted)))

    @When("the owner posts a reply {string} quoting {string} from {string}'s reply and {string} from {string}'s reply")
    fun postsQuotingTwo(text: String, q1: String, t1: String, q2: String, t2: String) =
        postQuoting(text, listOf(quote(t1, q1), quote(t2, q2)))

    @When("the owner posts a reply {string} with no quotes")
    fun postsNoQuotes(text: String) = postQuoting(text, emptyList())

    @When("the owner posts a reply {string} quoting {string} from a missing comment")
    fun postsQuotingMissing(text: String, quoted: String) =
        postQuoting(text, listOf(mapOf("targetId" to "no-such-comment", "text" to quoted)))

    @Then("{string}'s reply quotes {string}'s reply")
    fun replyQuotes(src: String, target: String) {
        assertNotNull(
            Html.quoteRefText(body(), replyId(src), replyId(target)),
            "expected ${src}'s reply to carry a forward quote anchor to ${target}'s reply in:\n${body()}",
        )
    }

    @Then("{string}'s quote of {string}'s reply shows {string}")
    fun quoteShows(src: String, target: String, snippet: String) {
        val text = Html.quoteRefText(body(), replyId(src), replyId(target))
        assertNotNull(text, "expected a quote anchor from ${src}'s reply to ${target}'s reply")
        assertTrue(text!!.contains(snippet), "expected the quote anchor to show \"$snippet\", was:\n$text")
    }

    @Then("{string}'s reply has no quote anchors")
    fun replyHasNoQuotes(src: String) {
        assertTrue(
            Html.quoteSources(body(), replyId(src)).isEmpty(),
            "expected NO forward quote anchors on ${src}'s reply in:\n${body()}",
        )
    }

    // ---- backward direction: backlinks (the "quoted by" side) ----

    // A second/anonymous quoting reply when the scenario only cares about the COUNT, not the quoter alias.
    @When("a reply quoting {string} from {string}'s reply is posted")
    fun aReplyQuoting(quoted: String, target: String) =
        postQuoting("Citing this.", listOf(quote(target, quoted)))

    @Then("{string}'s reply is quoted by {string}'s reply")
    fun replyQuotedBy(target: String, quoter: String) {
        assertTrue(
            Html.backlinkQuoters(body(), replyId(target)).contains(replyId(quoter)),
            "expected ${target}'s reply to be quoted by ${quoter}'s reply in:\n${body()}",
        )
    }

    @Then("{string}'s reply shows it was quoted {int} times")
    fun quotedNTimes(target: String, n: Int) {
        assertEquals(
            n, Html.quotedByCount(body(), replyId(target)),
            "expected ${target}'s reply quoted-by count = $n in:\n${body()}",
        )
    }

    @Then("{string}'s reply has {int} distinct quoted passages")
    fun distinctPassages(target: String, n: Int) {
        assertEquals(
            n, Html.backlinkPassages(body(), replyId(target)).size,
            "expected ${target}'s reply to have $n distinct quoted passages in:\n${body()}",
        )
    }

    @Then("{string}'s reply has no backlinks")
    fun noBacklinks(target: String) {
        assertEquals(
            0, Html.quotedByCount(body(), replyId(target)),
            "expected NO backlinks on ${target}'s reply in:\n${body()}",
        )
    }
}
