package com.aiforum.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
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
    // Diagnostics/opt-ins, defaulted so the Tier-1 test (and any positional caller) can ignore them.
    // logRawResponse: dump the unparsed HTTP body at DEBUG (the `debug` profile flips it on) so we can
    // see whether a model leaks reasoning inline in `content` or in a separate reasoning field.
    private val logRawResponse: Boolean = false,
    // disableThinking: send chat_template_kwargs.enable_thinking=false to turn the model's reasoning OFF
    // at generation time. Honoured only by servers/models with a thinking switch in their chat template
    // (Qwen3, vLLM/SGLang, LM Studio); a no-op for models like Gemma whose "thinking" is prompt-induced.
    private val disableThinking: Boolean = false,
) : LlmClient {

    private val log = LoggerFactory.getLogger(OpenAiLlmClient::class.java)

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
        @Value("\${aiforum.llm.openai.log-raw-response:false}") logRawResponse: Boolean,
        @Value("\${aiforum.llm.openai.disable-thinking:false}") disableThinking: Boolean,
    ) : this(
        RestClient.builder(), baseUrl, apiKey, defaultModel,
        temperature, maxTokens, pollMillis, rateLimitRetryAfterSeconds, logRawResponse, disableThinking,
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
            // Only present when the opt-in is on — omitted otherwise (NON_NULL) so servers that don't
            // understand it never see it. Turns the model's reasoning off at the template level.
            chatTemplateKwargs = if (disableThinking) mapOf("enable_thinking" to false) else null,
        )

        // Run the blocking POST on a daemon worker so the poll loop below can enforce the deadline and
        // cancellation without being stuck inside a socket read.
        val task = FutureTask { callOnce(payload) }
        Thread(task).apply { isDaemon = true; name = "openai-llm" }.start()

        val result = awaitWithin(task, request.timeout, cancellation)
        // Raw-body diagnostics (debug profile): the only way to know for certain whether a given model
        // leaks reasoning inline in `content` or splits it into a reasoning_content/reasoning field.
        if (logRawResponse) log.debug("LM Studio/OpenAI raw response — status={} body={}", result.status, result.body)
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        @get:JsonProperty("max_tokens") val maxTokens: Int,
        val stream: Boolean = false,
        // Forwarded to the model's chat template (e.g. {enable_thinking:false}); omitted when null.
        @get:JsonProperty("chat_template_kwargs") val chatTemplateKwargs: Map<String, Any>? = null,
    )

    private data class ChatMessage(val role: String, val content: String)
}
