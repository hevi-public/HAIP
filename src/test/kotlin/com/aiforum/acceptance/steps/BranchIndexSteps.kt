package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Then("the page shows no branch index")
    fun pageShowsNoBranchIndex() {
        assertFalse(
            Html.hasAttr(body(), "data-rail-box", "branch-index"),
            "expected NO branch-index box in:\n${body()}",
        )
    }
}
