package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the "in reply to" parent-quote anchor. The anchor carries data-in-reply-to="<parent id>"
 * and its text is the literal (truncated) quote of the parent — asserted via Html.inReplyToText, which
 * reads the anchor belonging to a specific child reply.
 */
class InReplyToSteps(
    private val world: ScenarioWorld,
) {
    private fun body(): String = world.lastBody ?: ""
    private fun replyId(alias: String): String =
        world.replyIds["$alias's reply"] ?: error("no remembered reply for \"$alias's reply\"")

    @Then("{string}'s reply has an in-reply-to anchor pointing at {string}'s reply")
    fun anchorPointsAt(child: String, parent: String) {
        val tag = Regex("<a\\b[^>]*data-in-reply-to=\"${Regex.escape(replyId(parent))}\"[^>]*>")
        assertTrue(
            tag.containsMatchIn(body()),
            "expected ${child}'s reply to link to ${parent}'s reply (${replyId(parent)}) in:\n${body()}",
        )
    }

    @Then("{string}'s reply has no in-reply-to anchor")
    fun noAnchor(child: String) {
        assertNull(
            Html.inReplyToText(body(), replyId(child)),
            "expected NO in-reply-to anchor on ${child}'s reply",
        )
    }

    @Then("{string}'s in-reply-to anchor quotes {string}")
    fun anchorQuotes(child: String, expected: String) {
        val text = Html.inReplyToText(body(), replyId(child))
        assertNotNull(text, "expected an in-reply-to anchor on ${child}'s reply")
        assertTrue(text!!.contains(expected), "expected anchor to quote \"$expected\", was:\n$text")
    }

    @Then("{string}'s in-reply-to anchor is truncated with an ellipsis")
    fun anchorTruncated(child: String) {
        val text = Html.inReplyToText(body(), replyId(child))
        assertNotNull(text, "expected an in-reply-to anchor on ${child}'s reply")
        assertTrue(text!!.contains("…"), "expected the anchor quote to end with an ellipsis, was:\n$text")
    }

    @Then("{string}'s in-reply-to anchor does not contain {string}")
    fun anchorDoesNotContain(child: String, needle: String) {
        val text = Html.inReplyToText(body(), replyId(child)) ?: ""
        assertTrue(!text.contains(needle), "expected anchor NOT to contain \"$needle\", was:\n$text")
    }
}
