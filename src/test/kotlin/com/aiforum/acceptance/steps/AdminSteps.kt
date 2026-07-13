package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the /admin statistics dashboard. Mirrors StarredPageSteps: drive the page over HTTP,
 * then assert numbers via the stable data-stat / data-value hooks the admin.kte template emits.
 */
class AdminSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    private fun body(): String = world.lastBody ?: ""

    @When("the owner visits the admin page")
    fun visitAdmin() {
        val resp = http.get("/admin")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the admin dashboard is shown")
    fun adminShown() {
        assertEquals(200, world.lastStatus, "expected /admin to return 200")
        assertTrue(body().contains("data-admin-page"), "expected the data-admin-page marker in:\n${body()}")
    }

    @Then("the admin statistic {string} is {int}")
    fun adminStatIs(key: String, expected: Int) {
        val tag = Regex("<[^>]*data-stat=\"${Regex.escape(key)}\"[^>]*>").find(body())?.value
            ?: error("no element with data-stat=\"$key\" in:\n${body()}")
        val value = Regex("data-value=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: error("data-stat=\"$key\" carried no data-value in:\n${body()}")
        assertEquals(expected.toString(), value, "data-stat=\"$key\"")
    }

    @Then("the admin statistic {string} links to {string}")
    fun adminStatLinksTo(key: String, href: String) {
        val start = body().indexOf("data-stat=\"$key\"")
        assertTrue(start >= 0, "no data-stat=\"$key\" in:\n${body()}")
        val end = body().indexOf("</li>", start).let { if (it >= 0) it else body().length }
        val block = body().substring(start, end)
        val found = Regex("href=\"([^\"]*)\"").find(block)?.groupValues?.get(1)
        assertEquals(href, found, "link for data-stat=\"$key\"")
    }

    @When("the owner navigates to {string}")
    fun navigateTo(path: String) {
        val resp = http.get(path)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the comments list has an entry for {string}'s reply")
    fun commentsListHasEntry(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertTrue(
            Html.hasAttr(body(), "data-admin-comment", id),
            "expected data-admin-comment=\"$id\" in:\n${body()}",
        )
    }

    @Then("the comments list entry for {string}'s reply links to its thread")
    fun commentsListEntryLinks(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertTrue(
            body().contains("#reply-$id\""),
            "expected a permalink ending #reply-$id in:\n${body()}",
        )
    }

    @Then("the comments list is empty")
    fun commentsListEmpty() {
        assertTrue(
            Html.hasAttr(body(), "data-admin-list-empty", "true"),
            "expected data-admin-list-empty=\"true\" in:\n${body()}",
        )
    }
}
