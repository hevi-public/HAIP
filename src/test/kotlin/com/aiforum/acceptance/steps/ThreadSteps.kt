package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for thread-level operations (create, view). The When step POSTs to /threads; the Then
 * steps assert against the rendered thread page. Creating a thread now auto-summons the room (§2), so the
 * create step settles the drafted reply/replies (mirroring the browser's htmx poll) before the Then steps
 * read the spy / page.
 */
class ThreadSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @When("the owner creates a thread {string} asking {string} of {string}")
    fun createThread(title: String, text: String, personaList: String) {
        val personaIds = personaList.split(",").map { it.trim() }
        val resp = http.postJson(
            "/threads",
            mapOf("title" to title, "text" to text, "personaIds" to personaIds),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        world.threadId = resp.body?.let {
            Regex("""data-thread-id="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
        // Creating a thread auto-summons the room (Whole Topic + Anyone): the create response surfaces the
        // in-flight DRAFTING node(s). Settle them so the dispatcher + persona calls land in the LlmClient
        // spy and the thread page shows the posted replies the Then steps assert on.
        settle.awaitAllSettled(Html.allReplyIds(resp.body ?: ""))
    }

    @When("the owner starts a thread titled {string} from the browser")
    fun startThreadFromBrowser(title: String) {
        // The home page's form posts form-urlencoded (the browser default), then PRG-redirects onto the
        // thread. We locate the freshly-created thread on the home page by title — robust whether or not
        // the HTTP client auto-follows the redirect.
        http.postForm("/threads", mapOf("title" to title))
        val home = http.get("/").body ?: ""
        world.threadId = Regex("""data-thread-id="([^"]+)"\s+data-thread-title="${Regex.escape(title)}"""")
            .find(home)?.groupValues?.get(1)
        assertTrue(world.threadId != null, "expected thread \"$title\" on the home page after create:\n$home")
    }

    @When("the owner starts a thread titled {string} with body {string} from the browser")
    fun startThreadWithBodyFromBrowser(title: String, body: String) {
        // Same browser form path as the title-only step, but the new-thread form now carries a body
        // (name="text") alongside the title — the opening post's content.
        http.postForm("/threads", mapOf("title" to title, "text" to body))
        val home = http.get("/").body ?: ""
        world.threadId = Regex("""data-thread-id="([^"]+)"\s+data-thread-title="${Regex.escape(title)}"""")
            .find(home)?.groupValues?.get(1)
        assertTrue(world.threadId != null, "expected thread \"$title\" on the home page after create:\n$home")
    }

    @Then("the thread page shows the post body {string}")
    fun threadPageShowsBody(body: String) {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        val html = world.lastBody ?: ""
        assertTrue(html.contains("data-op-body"), "expected an OP body element in:\n$html")
        assertTrue(Html.contains(html, body), "expected body \"$body\" in thread page:\n$html")
    }

    @Then("the thread exists with title {string}")
    fun threadExistsWithTitle(title: String) {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(
            Html.contains(world.lastBody ?: "", title),
            "expected title \"$title\" in thread page:\n${world.lastBody}",
        )
    }

    @When("the owner views the thread page")
    fun viewThreadPage() {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the thread shows the opening post {string}")
    fun threadShowsOpeningPost(text: String) {
        // The opening question renders on the post node itself (data-op-body), under the title — not as a
        // persona reply, not dropped. Re-fetch so this asserts the persisted+rendered page, not the create
        // response.
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(
            Html.contains(body, "data-op-body"),
            "expected an opening-post body element (data-op-body) in:\n$body",
        )
        assertTrue(
            Html.contains(body, text),
            "expected the opening post \"$text\" on the thread page:\n$body",
        )
    }

    @Then("the thread shows the waiting-on-the-room empty state")
    fun threadShowsWaitingState() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-empty-state", "waiting"),
            "expected data-empty-state=\"waiting\" in:\n${world.lastBody}",
        )
    }
}
