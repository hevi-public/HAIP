package com.aiforum.acceptance.support

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.io.ByteArrayResource
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

    /**
     * Like [postForm] but stamps the `HX-Request: true` header htmx sets on every request — so a step can
     * exercise the htmx-only error path. For these, HtmxErrorAdvice replies with the mapped non-2xx status
     * + an `HX-Trigger: app:error` header + an EMPTY body (toast-only, nothing to swap); a non-htmx request
     * is rethrown to Boot's default handling (Whitelabel at the real status). Mirrors the single header the
     * advice branches on; nothing else about the call changes.
     */
    fun postFormHtmx(path: String, form: Map<String, Any?>): ResponseEntity<String> {
        val map = LinkedMultiValueMap<String, String>()
        form.forEach { (k, v) -> if (v != null) map.add(k, v.toString()) }
        return client.post().uri(url(path))
            .header("HX-Request", "true")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(map)
            .exchange { _, res -> capture(res) }
    }

    fun post(path: String): ResponseEntity<String> =
        client.post().uri(url(path)).exchange { _, res -> capture(res) }

    /**
     * multipart/form-data POST — text [fields] plus named image [files]. Mirrors the browser's image
     * upload (the multipart controller handlers). Each file part needs a filename, so we wrap the bytes in
     * a ByteArrayResource that reports one.
     */
    fun postMultipart(
        path: String,
        fields: Map<String, String> = emptyMap(),
        files: Map<String, ByteArray> = emptyMap(),
    ): ResponseEntity<String> {
        val map = LinkedMultiValueMap<String, Any>()
        fields.forEach { (k, v) -> map.add(k, v) }
        files.forEach { (k, bytes) ->
            map.add(k, object : ByteArrayResource(bytes) {
                override fun getFilename() = "$k.png"
            })
        }
        return client.post().uri(url(path)).contentType(MediaType.MULTIPART_FORM_DATA).body(map)
            .exchange { _, res -> capture(res) }
    }

    // Preserve the response headers too, not just status + body: T1.4 asserts on the HX-Trigger header
    // the htmx error advice emits. Existing callers only read status/body, so this is backward-compatible.
    private fun capture(res: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): ResponseEntity<String> =
        ResponseEntity.status(res.statusCode).headers(res.headers).body(res.bodyTo(String::class.java) ?: "")
}
