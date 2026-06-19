package com.aiforum.acceptance.support

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * Thin wrapper over RestClient so the step layer speaks paths, not URLs — and so a future Playwright
 * swap touches only this file (the same DOM-agnostic .feature files re-point at it).
 *
 * Spring Boot 4 removed TestRestTemplate; we use the production RestClient with `.exchange()`, which
 * (unlike `.retrieve()`) does NOT throw on 4xx/5xx — exactly what we want for asserting on 404s while
 * the controllers don't exist yet.
 */
@Component
@Profile("test")
class HttpClient(private val env: Environment) {

    private val client = RestClient.create()

    // Read the port at CALL time (not construction): under RANDOM_PORT it's only set once the server
    // is up, which is after this bean is created. Reading it lazily avoids a placeholder failure
    // during context refresh.
    private fun url(path: String): String {
        val port = env.getProperty("local.server.port")
            ?: error("local.server.port not set — is the test using webEnvironment=RANDOM_PORT?")
        return "http://localhost:$port$path"
    }

    fun get(path: String): ResponseEntity<String> =
        client.get().uri(url(path)).exchange { _, res -> capture(res) }

    fun postJson(path: String, body: Any): ResponseEntity<String> =
        client.post().uri(url(path)).contentType(MediaType.APPLICATION_JSON).body(body)
            .exchange { _, res -> capture(res) }

    fun postForm(path: String, form: Map<String, Any?>): ResponseEntity<String> {
        val map = LinkedMultiValueMap<String, String>()
        form.forEach { (k, v) -> if (v != null) map.add(k, v.toString()) }
        return client.post().uri(url(path)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(map)
            .exchange { _, res -> capture(res) }
    }

    fun post(path: String): ResponseEntity<String> =
        client.post().uri(url(path)).exchange { _, res -> capture(res) }

    private fun capture(res: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): ResponseEntity<String> =
        ResponseEntity.status(res.statusCode).body(res.bodyTo(String::class.java) ?: "")
}
