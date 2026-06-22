package com.aiforum.llm

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Alternative [LlmClient] that talks the OpenAI **Chat Completions** API over HTTP — used to run
 * generation against any OpenAI-compatible server (LM Studio serving a local Gemma model is the M1
 * target). Selected over [ProcessLlmClient] by `aiforum.llm.provider: openai` (see the `openai` profile);
 * under the `test` profile a @Primary ScriptableLlmClient replaces both, so the acceptance suite never
 * does real IO.
 *
 * Like ProcessLlmClient this is the genuinely un-fakeable part — the socket, the deadline, cancellation —
 * while the pure classification of the result lives in [OpenAiResponseParser]. The HTTP call is run on a
 * daemon worker and polled with the SAME loop ProcessLlmClient uses, so a per-request timeout and a
 * tripped [CancellationToken] map onto identical behaviour (interrupt the worker, surface Timeout/Cancelled)
 * regardless of provider.
 *
 * `open` to mirror ProcessLlmClient; the genuine test seam is the [RestClient.Builder] taken by the
 * primary constructor, which a Tier-1 test binds a `MockRestServiceServer` to.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.llm", name = ["provider"], havingValue = "openai")
open class OpenAiLlmClient(
    restClientBuilder: RestClient.Builder,
    baseUrl: String,
    private val apiKey: String,
    // Reuses `default-model` (the same key ProcessLlmClient falls back to) so model selection is one
    // concept across providers: a persona's pinned PersonaRef.model wins, else this default, else blank.
    private val defaultModel: String,
    private val temperature: Double,
    private val maxTokens: Int,
    private val pollMillis: Long,
    private val rateLimitRetryAfterSeconds: Long,
) : LlmClient {

    /**
     * The constructor Spring uses. It builds its own [RestClient] from the static `RestClient.builder()`
     * rather than injecting a `RestClient.Builder` bean — this app autoconfigures none, and the static
     * builder already carries the Jackson converters we serialise the request with. The primary
     * constructor (above) takes a builder so a Tier-1 test can bind a `MockRestServiceServer` to it.
     */
    @Autowired
    constructor(
        @Value("\${aiforum.llm.openai.base-url:http://localhost:1234/v1}") baseUrl: String,
        @Value("\${aiforum.llm.openai.api-key:}") apiKey: String,
        @Value("\${aiforum.llm.default-model:}") defaultModel: String,
        @Value("\${aiforum.llm.openai.temperature:0.7}") temperature: Double,
        @Value("\${aiforum.llm.openai.max-tokens:1024}") maxTokens: Int,
        @Value("\${aiforum.llm.poll-millis:100}") pollMillis: Long,
        @Value("\${aiforum.llm.rate-limit-retry-after-seconds:300}") rateLimitRetryAfterSeconds: Long,
    ) : this(
        RestClient.builder(), baseUrl, apiKey, defaultModel,
        temperature, maxTokens, pollMillis, rateLimitRetryAfterSeconds,
    )

    // Absolute endpoint, built by hand rather than via RestClient's baseUrl: a leading-slash path against
    // a baseUrl that itself has a path (".../v1") would resolve to ".../chat/completions" and drop the /v1.
    private val completionsUrl = baseUrl.trimEnd('/') + "/chat/completions"
    private val http: RestClient = restClientBuilder.build()

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        // The persona's pinned model wins; a blank one falls back to the configured default. Sent as-is
        // (LM Studio uses its loaded model regardless; hosted servers want the real id here).
        val model = request.persona.model.ifBlank { defaultModel }
        val payload = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", request.context.personaSystemPrompt),
                ChatMessage("user", PromptRenderer.renderTask(request.context, request.persona.name)),
            ),
            temperature = temperature,
            maxTokens = maxTokens,
        )

        // Run the blocking POST on a daemon worker so the poll loop below can enforce the deadline and
        // cancellation without being stuck inside a socket read.
        val task = FutureTask { callOnce(payload) }
        Thread(task).apply { isDaemon = true; name = "openai-llm" }.start()

        val result = awaitWithin(task, request.timeout, cancellation)
        return OpenAiResponseParser.parse(
            result.status,
            result.body,
            result.retryAfter,
            Duration.ofSeconds(rateLimitRetryAfterSeconds),
        )
    }

    /**
     * Wait for the HTTP worker, bounded by [timeout] and cooperatively cancellable — the same runaway-proof
     * loop as ProcessLlmClient: each iteration blocks at most `pollMs`, and the only exits are the call
     * finishing, the token tripping, or the monotonic deadline firing (nanoTime subtraction is
     * wraparound-safe; the poll interval is floored at 1ms so a misconfigured 0 can't busy-spin).
     */
    private fun awaitWithin(
        task: FutureTask<HttpResult>,
        timeout: Duration,
        cancellation: CancellationToken,
    ): HttpResult {
        val pollMs = pollMillis.coerceAtLeast(1)
        val timeoutNanos = timeout.toNanos().coerceAtLeast(0)
        val start = System.nanoTime()
        try {
            while (true) {
                if (cancellation.isCancelled) {
                    task.cancel(true)
                    throw LlmException.Cancelled()
                }
                try {
                    return task.get(pollMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    if (System.nanoTime() - start >= timeoutNanos) {
                        task.cancel(true)
                        throw LlmException.Timeout()
                    }
                    // else: deadline not yet reached — re-check cancellation and keep waiting
                } catch (_: ExecutionException) {
                    // The HTTP call itself failed before any response arrived (server down, connection
                    // refused, socket error). Surface a retryable upstream fault; status 0 = "no HTTP
                    // response", which OpenAiResponseParser maps to ProcessError.
                    throw LlmException.ProcessError(0)
                }
            }
        } catch (_: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw LlmException.Cancelled()
        }
    }

    /** One blocking POST. Uses exchange() so non-2xx responses come back for classification rather than throwing. */
    private fun callOnce(payload: ChatRequest): HttpResult =
        http.post()
            .uri(completionsUrl)
            .headers { headers -> if (apiKey.isNotBlank()) headers.setBearerAuth(apiKey) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .exchange { _, response ->
                val body = runCatching {
                    response.body.bufferedReader(Charsets.UTF_8).readText()
                }.getOrDefault("")
                HttpResult(
                    status = response.statusCode.value(),
                    body = body,
                    retryAfter = response.headers.getFirst("Retry-After"),
                )
            }

    private data class HttpResult(val status: Int, val body: String, val retryAfter: String?)

    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        @get:JsonProperty("max_tokens") val maxTokens: Int,
        val stream: Boolean = false,
    )

    private data class ChatMessage(val role: String, val content: String)
}
