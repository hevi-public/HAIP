package com.aiforum.tier1

import com.aiforum.shortcut.HttpShortcutClient
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Tier-1: the genuinely un-fakeable plumbing of [HttpShortcutClient] — request shaping (path, the
 * `query`/`page_size` params, the `Shortcut-Token` auth header), JSON → view-model parsing, and that an
 * API error surfaces as a thrown exception (the layer [com.aiforum.shortcut.ShortcutService] catches).
 * A `MockRestServiceServer` is bound to the injected builder, exactly like OpenAiLlmClientTest.
 */
@Tag("tier1")
class HttpShortcutClientTest {

    private val baseUrl = "https://api.app.shortcut.com/api/v3"

    private fun client(token: String = "secret-token"): Pair<HttpShortcutClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return HttpShortcutClient(builder, baseUrl, token) to server
    }

    @Test
    fun `searchStories sends the query, page size and token, and parses the stories`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/search/stories")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Shortcut-Token", "secret-token"))
            .andExpect(queryParam("query", "is:started"))
            .andExpect(queryParam("page_size", "5"))
            .andRespond(
                withSuccess(
                    """{"data":[{"id":123,"name":"Fix the login bug","story_type":"bug",""" +
                        """"workflow_state_id":500,"app_url":"https://app.shortcut.com/acme/story/123"}],"total":1}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val cards = client.searchStories("is:started", 5)

        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals(123L, card.publicId)
        assertEquals("Fix the login bug", card.name)
        assertEquals("bug", card.type)
        assertEquals(500L, card.stateId)
        assertEquals("", card.state) // state name is resolved later, by the service
        assertEquals("https://app.shortcut.com/acme/story/123", card.url)
        server.verify()
    }

    @Test
    fun `page size is clamped to the API maximum of 25`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/search/stories")))
            .andExpect(queryParam("page_size", "25"))
            .andRespond(withSuccess("""{"data":[]}""", MediaType.APPLICATION_JSON))

        client.searchStories("is:started", 1000)
        server.verify()
    }

    @Test
    fun `workflowStates flattens the workflows into an id to name map`() {
        val (client, server) = client()
        server.expect(requestTo(containsString("/workflows")))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Shortcut-Token", "secret-token"))
            .andRespond(
                withSuccess(
                    """[{"states":[{"id":500,"name":"In Progress"},{"id":501,"name":"Done"}]}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertEquals(mapOf(500L to "In Progress", 501L to "Done"), client.workflowStates())
        server.verify()
    }

    @Test
    fun `an unauthorized response surfaces as a thrown error`() {
        val (client, server) = client(token = "bad")
        server.expect(requestTo(containsString("/search/stories")))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"message":"Invalid token"}"""))

        assertThrows(RestClientResponseException::class.java) {
            client.searchStories("is:started", 5)
        }
    }
}
