package com.aiforum.shortcut

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * The real [ShortcutClient] — talks the Shortcut REST API (v3) over HTTPS, authenticating with the
 * `Shortcut-Token` header. Read-only: only GET is ever issued.
 *
 * Created only when `aiforum.shortcut.enabled: true` (and never under the `test` profile, where a
 * scriptable fake takes its place). Mirrors [com.aiforum.llm.OpenAiLlmClient]: `open` with a primary
 * constructor that takes a [RestClient.Builder] so a Tier-1 test can bind a `MockRestServiceServer` to
 * it, and an `@Autowired` constructor that builds its own client from the static builder.
 *
 * Absolute URIs are built with [UriComponentsBuilder] rather than a RestClient base-url: the base itself
 * carries a path (`/api/v3`), and a leading-slash relative path would drop it (the same gotcha
 * OpenAiLlmClient documents).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.shortcut", name = ["enabled"], havingValue = "true")
open class HttpShortcutClient(
    restClientBuilder: RestClient.Builder,
    private val baseUrl: String,
    private val token: String,
) : ShortcutClient {

    @Autowired
    constructor(props: ShortcutProperties) : this(RestClient.builder(), props.baseUrl, props.apiToken)

    private val http: RestClient = restClientBuilder.build()

    override fun searchStories(query: String, pageSize: Int): List<StoryCard> {
        val uri = UriComponentsBuilder.fromUriString(baseUrl)
            .path("/search/stories")
            .queryParam("query", query)
            .queryParam("page_size", pageSize.coerceIn(1, 25))
            .build()
            .encode()
            .toUri()
        val response = http.get()
            .uri(uri)
            .header(TOKEN_HEADER, token)
            .retrieve()
            .body(ScSearchStories::class.java)
            ?: ScSearchStories()
        return response.data.map(ShortcutMapper::toCard)
    }

    override fun workflowStates(): Map<Long, String> {
        val uri = UriComponentsBuilder.fromUriString(baseUrl).path("/workflows").build().encode().toUri()
        val workflows = http.get()
            .uri(uri)
            .header(TOKEN_HEADER, token)
            .retrieve()
            .body(Array<ScWorkflow>::class.java)
            ?: emptyArray()
        return ShortcutMapper.stateNames(workflows.toList())
    }

    private companion object {
        const val TOKEN_HEADER = "Shortcut-Token"
    }
}
