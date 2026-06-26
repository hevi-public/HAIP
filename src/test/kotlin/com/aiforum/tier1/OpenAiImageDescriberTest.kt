package com.aiforum.tier1

import ch.qos.logback.classic.Level
import com.aiforum.images.DescribeRequest
import com.aiforum.images.OpenAiImageDescriber
import com.aiforum.images.VisionUnavailableException
import com.aiforum.testsupport.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.Base64

/**
 * Tier-1: the genuinely un-fakeable plumbing of [OpenAiImageDescriber] — the OpenAI vision request shape
 * (a single user turn whose content is [text-instruction, base64 data-URI image block]), the inline
 * response parse (`choices.firstOrNull().message.content`), and the three failure paths that surface
 * [VisionUnavailableException]: vision disabled, no caption (empty choices), and a non-2xx transport
 * failure (mapped via the describer's `onStatus` handler so an endpoint outage degrades to a FAILED
 * caption at the service seam rather than propagating a 500). HTTP is mocked at the one seam via a
 * `MockRestServiceServer` bound to the injected builder, exactly as OpenAiLlmClientTest does for the
 * generation client.
 */
@Tag("tier1")
class OpenAiImageDescriberTest {

    private val url = "http://localhost:1234/v1/chat/completions"
    private val pngBytes = byteArrayOf(0x1, 0x2, 0x3, 0x4)

    private fun request() = DescribeRequest(imageBytes = pngBytes, mimeType = "image/png")

    private fun envelope(content: String) =
        """{"id":"x","object":"chat.completion","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"$content"}}]}"""

    /** A reply with no choices — the empty-choices path the describer maps to VisionUnavailable. */
    private fun emptyChoicesEnvelope() = """{"id":"x","object":"chat.completion","choices":[]}"""

    /**
     * A describer whose HTTP goes to a MockRestServiceServer. `enabled` is true so the request is actually
     * issued (the disabled short-circuit is a pure branch with no IO and is not the subject here).
     */
    private fun mockDescriber(model: String = ""): Pair<OpenAiImageDescriber, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val describer = OpenAiImageDescriber(
            restClientBuilder = builder,
            baseUrl = "http://localhost:1234/v1",
            apiKey = "",
            model = model,
            enabled = true,
            prompt = "Describe this image.",
            maxTokens = 512,
        )
        return describer to server
    }

    @Test
    fun `a successful vision completion returns the trimmed caption`() {
        val (describer, server) = mockDescriber()
        val expectedDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("\$.messages[0].role").value("user"))
            .andExpect(jsonPath("\$.messages[0].content[0].type").value("text"))
            .andExpect(jsonPath("\$.messages[0].content[0].text").value("Describe this image."))
            .andExpect(jsonPath("\$.messages[0].content[1].type").value("image_url"))
            .andExpect(jsonPath("\$.messages[0].content[1].image_url.url").value(expectedDataUri))
            .andExpect(jsonPath("\$.max_tokens").value(512))
            .andRespond(withSuccess(envelope("  a small red square  "), MediaType.APPLICATION_JSON))

        val caption = describer.describe(request())

        assertEquals("a small red square", caption)
        server.verify()
    }

    @Test
    fun `an empty choices array surfaces VisionUnavailableException`() {
        val (describer, server) = mockDescriber()
        server.expect(requestTo(url))
            .andRespond(withSuccess(emptyChoicesEnvelope(), MediaType.APPLICATION_JSON))

        assertThrows(VisionUnavailableException::class.java) {
            describer.describe(request())
        }
        server.verify()
    }

    @Test
    fun `a non-2xx response is mapped to VisionUnavailableException and logged as a structured event`() {
        // A vision-endpoint outage is a foreseeable, degradable condition: the describer's onStatus handler
        // maps any non-2xx into VisionUnavailableException — the SAME path the disabled and no-caption cases
        // take — so AttachmentService.describe()'s catch(Throwable) degrades it to a FAILED caption instead
        // of a 500 reaching the frontend. The outage must also be visible: assert the structured
        // `vision.unavailable` event (id + status field), the operator-facing contract a log tool keys off.
        val (describer, server) = mockDescriber()
        server.expect(requestTo(url)).andRespond(withServerError().body("boom"))

        LogCapture.on(OpenAiImageDescriber::class.java).use { logs ->
            assertThrows(VisionUnavailableException::class.java) {
                describer.describe(request())
            }
            val e = logs.withEvent("vision.unavailable").single()
            assertEquals(Level.WARN, e.level)
            assertEquals("500", logs.keyValue(e, "status"))
        }
        server.verify()
    }
}
