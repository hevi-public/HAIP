package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the thread rail's first occupant — the branch index (a TOC of posted comments). Asserts
 * on the stable data-* hooks: data-rail-box="branch-index" marks the box, and each entry carries
 * data-branch-index-entry="<reply id>" pointing at the comment's anchor (id="reply-<reply id>").
 */
class BranchIndexSteps(
    private val world: ScenarioWorld,
) {
    private fun body(): String = world.lastBody ?: ""
    private fun entries(): List<String> = Html.attrValues(body(), "data-branch-index-entry")

    @Then("the thread rail shows a branch index")
    fun railShowsBranchIndex() {
        assertTrue(
            Html.hasAttr(body(), "data-rail-box", "branch-index"),
            "expected a branch-index box (data-rail-box=\"branch-index\") in:\n${body()}",
        )
    }

    @Then("the branch index lists {int} entries")
    fun branchIndexListsEntries(count: Int) {
        assertEquals(count, entries().size, "branch index entries in:\n${body()}")
    }

    @Then("the branch index has an entry for {string}'s reply")
    fun branchIndexHasEntryFor(persona: String) {
        val id = world.replyIds["$persona's reply"]
            ?: error("no remembered reply id for \"$persona's reply\"")
        assertTrue(
            entries().contains(id),
            "expected a branch index entry for $persona's reply ($id) in:\n${body()}",
        )
    }

    @Then("every branch index entry links to a comment anchored on the page")
    fun everyEntryLinksToAnchor() {
        val orphans = entries().filterNot { Html.hasAttr(body(), "id", "reply-$it") }
        assertTrue(orphans.isEmpty(), "branch index entries with no anchor on the page: $orphans")
    }

    @Then("the branch index entry for {string}'s reply shows {string}")
    fun branchEntryShows(persona: String, text: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for \"$persona's reply\"")
        val entry = Html.branchEntryText(body(), id)
        assertTrue(entry != null && entry.contains(text), "expected entry for $persona to show \"$text\", was:\n$entry")
    }

    @Then("the branch index entry for {string}'s reply is truncated with an ellipsis")
    fun branchEntryTruncated(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for \"$persona's reply\"")
        val entry = Html.branchEntryText(body(), id)
        assertTrue(entry != null && entry.contains("…"), "expected entry for $persona to end with an ellipsis, was:\n$entry")
    }

    @Then("the branch index entry for {string}'s reply does not contain {string}")
    fun branchEntryDoesNotContain(persona: String, needle: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for \"$persona's reply\"")
        val entry = Html.branchEntryText(body(), id) ?: ""
        assertTrue(!entry.contains(needle), "expected entry for $persona NOT to contain \"$needle\", was:\n$entry")
    }

    @Then("the branch index shows an empty state")
    fun branchIndexShowsEmptyState() {
        assertTrue(
            Html.hasAttr(body(), "data-branch-index-empty", "true"),
            "expected the branch-index empty state (data-branch-index-empty=\"true\") in:\n${body()}",
        )
    }
}
