package com.aiforum.images

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.Base64

/**
 * The real [ImageDescriber]: captions an image via an OpenAI-compatible vision endpoint (LM Studio
 * serving a local vision model is the target). It reuses the generation OpenAI base-url by default so a
 * single LM Studio instance can serve both — but the model is configured separately
 * (aiforum.images.vision.model), because the chat model is usually NOT vision-capable.
 *
 * Always loaded under `!test` (no @ConditionalOnProperty) so the context always has an ImageDescriber
 * bean — when describe is disabled it simply throws [VisionUnavailableException], which the service turns
 * into a graceful FAILED caption. The `test` profile's @Primary fake replaces it.
 *
 * `open` and primary-constructor-takes-a-builder, mirroring OpenAiLlmClient, so a Tier-1 test can bind a
 * MockRestServiceServer.
 */
@Component
@Profile("!test")
open class OpenAiImageDescriber(
    restClientBuilder: RestClient.Builder,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val enabled: Boolean,
    private val prompt: String,
    private val maxTokens: Int,
) : ImageDescriber {

    @Autowired
    constructor(
        // Falls back to the generation base-url so one LM Studio serves both; override to point vision at a
        // different server.
        @Value("\${aiforum.images.vision.base-url:\${aiforum.llm.openai.base-url:http://localhost:1234/v1}}") baseUrl: String,
        @Value("\${aiforum.images.vision.api-key:\${aiforum.llm.openai.api-key:}}") apiKey: String,
        @Value("\${aiforum.images.vision.model:}") model: String,
        @Value("\${aiforum.images.describe.enabled:false}") enabled: Boolean,
        @Value("\${aiforum.images.vision.prompt:Describe this image factually and concisely for a reader who cannot see it. If it shows code, a terminal, or a config/log file, transcribe it verbatim inside a fenced markdown code block with the correct language tag. Note any other text, diagrams, charts, or UI shown.}") prompt: String,
        @Value("\${aiforum.images.vision.max-tokens:512}") maxTokens: Int,
    ) : this(RestClient.builder(), baseUrl, apiKey, model, enabled, prompt, maxTokens)

    private val completionsUrl = baseUrl.trimEnd('/') + "/chat/completions"
    private val http: RestClient = restClientBuilder.build()

    override fun describe(request: DescribeRequest): String {
        if (!enabled) {
            throw VisionUnavailableException(
                "vision is disabled — set aiforum.images.describe.enabled=true and bring up a vision model",
            )
        }
        // A blank model is sent as-is and the server uses its loaded model (LM Studio), matching the
        // generation client's behaviour — so describe works out of the box without pinning a model id.
        val dataUri = "data:${request.mimeType};base64," + Base64.getEncoder().encodeToString(request.imageBytes)
        val payload = ChatRequest(
            model = model,
            // OpenAI vision shape: a single user turn whose content is [text-instruction, image_url block].
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = listOf(
                        ContentPart.text(prompt),
                        ContentPart.image(dataUri),
                    ),
                ),
            ),
            maxTokens = maxTokens,
        )
        val body = http.post()
            .uri(completionsUrl)
            .headers { if (apiKey.isNotBlank()) it.setBearerAuth(apiKey) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(ChatResponse::class.java)
        val caption = body?.choices?.firstOrNull()?.message?.content?.trim()
        return caption?.takeIf { it.isNotBlank() }
            ?: throw VisionUnavailableException("vision model returned no caption")
    }

    // --- wire types (OpenAI Chat Completions, vision content blocks) ------------------------------

    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @get:JsonProperty("max_tokens") val maxTokens: Int,
        val stream: Boolean = false,
    )

    private data class ChatMessage(val role: String, val content: List<ContentPart>)

    private data class ContentPart(
        val type: String,
        val text: String? = null,
        @get:JsonProperty("image_url") val imageUrl: ImageUrl? = null,
    ) {
        companion object {
            fun text(t: String) = ContentPart(type = "text", text = t)
            fun image(url: String) = ContentPart(type = "image_url", imageUrl = ImageUrl(url))
        }
    }

    private data class ImageUrl(val url: String)

    private data class ChatResponse(val choices: List<Choice> = emptyList())
    private data class Choice(val message: Message? = null)
    private data class Message(val content: String? = null)
}
