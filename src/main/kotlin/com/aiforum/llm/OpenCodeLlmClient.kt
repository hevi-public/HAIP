package com.aiforum.llm

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.observability.event
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Third [LlmClient]: wraps the **opencode** agent CLI (`opencode run --format json`) as a streaming text
 * backend. Selected by `aiforum.llm.provider: opencode`. Like [ProcessLlmClient] it is the genuinely
 * un-fakeable part — spawning, the deadline, cancellation — while the pure NDJSON→[AguiEvent] normalisation
 * and result classification live in [OpenCodeStreamParser] (Tier 0).
 *
 * Two opencode-specific shapes:
 *  - **No inline system prompt.** opencode has no `--system-prompt` flag (it uses configured agents), so the
 *    persona's system prompt is folded into the message (prepended to the rendered task). The whole prompt is
 *    passed as one positional argv element via ProcessBuilder (no shell, so no escaping; forum-sized prompts
 *    stay well under ARG_MAX).
 *  - **Model is `provider/model`.** opencode addresses models as e.g. `lmstudio/qwen/qwen3.5-9b` or
 *    `anthropic/claude-…`; a persona's pinned `PersonaRef.model` (or `default-model`) must use that form.
 *    opencode itself must have that provider configured/authed — that's opencode's concern, not the app's.
 *
 * `open` so the Tier-1 test can substitute [spawn] with a controlled `/bin/sh` subprocess and exercise the
 * stream parsing + timeout/cancel/exit-code plumbing without the real `opencode` binary or a model call.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.llm", name = ["provider"], havingValue = "opencode")
open class OpenCodeLlmClient(
    @Value("\${aiforum.llm.opencode.command:opencode}") private val command: String = "opencode",
    // Reuses `default-model` (one model concept across providers); for opencode it must be `provider/model`.
    @Value("\${aiforum.llm.default-model:}") private val defaultModel: String = "",
    // Optional opencode agent (its own prompt/tool profile); blank => opencode's default agent.
    @Value("\${aiforum.llm.opencode.agent:}") private val agent: String = "",
    // Process working dir = opencode's project dir (it reads opencode.json/AGENTS.md from here). Blank =>
    // system temp, so the host project's config doesn't leak into the persona's run. Point it at a dir
    // holding an opencode.json when the provider (e.g. lmstudio) is configured project-locally.
    @Value("\${aiforum.llm.working-dir:}") private val workingDir: String = "",
    @Value("\${aiforum.llm.poll-millis:100}") private val pollMillis: Long = 100,
) : LlmClient {

    private val log = LoggerFactory.getLogger(OpenCodeLlmClient::class.java)

    private companion object {
        const val KILL_GRACE_MILLIS = 500L
        const val STREAM_GRACE_MILLIS = 2_000L
        const val EV_SPAWN = "llm.spawn"
        const val EV_TIMEOUT = "llm.timeout"
        const val EV_CANCELLED = "llm.cancelled"
    }

    // The blocking path (retry/regenerate) runs the same opencode pipeline with a no-op sink.
    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse =
        generate(request, cancellation, AguiEventSink { })

    override fun generate(request: LlmRequest, cancellation: CancellationToken, sink: AguiEventSink): LlmResponse {
        sink.emit(AguiEvent.RunStarted(request.runId))
        try {
            val model = request.persona.model.ifBlank { defaultModel }
            log.atDebug().setMessage("spawning {} run for persona {} (model {})")
                .addArgument(command).addArgument(request.persona.name).addArgument(model.ifBlank { "(opencode default)" })
                .event(EV_SPAWN).addKeyValue("persona", request.persona.name).addKeyValue("model", model)
                .log()

            val process = spawn(buildArgs(model, message(request)))
            process.outputStream.close() // opencode reads the message from argv, not stdin

            val parser = OpenCodeStreamParser(request.runId)
            // Reading lines IS the drain; the callback runs per NDJSON line as opencode emits it.
            val stdout = drainLines(process.inputStream) { line -> parser.onLine(line).forEach(sink::emit) }
            val stderr = drain(process.errorStream)

            awaitProcess(process, request.timeout, cancellation, request.persona.name)
            await(stderr)
            awaitDrain(stdout) // barrier: all events parsed before we classify

            val response = parser.toResponse(process.exitValue())
            sink.emit(AguiEvent.RunFinished(request.runId))
            return response
        } catch (e: Throwable) {
            sink.emit(AguiEvent.RunError(request.runId, e.message ?: "generation failed"))
            throw e
        }
    }

    /** opencode has no system-prompt flag, so fold the persona's system prompt into the message. */
    private fun message(request: LlmRequest): String =
        request.context.personaSystemPrompt.trim() + "\n\n" + PromptRenderer.renderTask(request.context, request.persona.name)

    private fun buildArgs(model: String, message: String): List<String> = buildList {
        add(command)
        add("run")
        add("--format"); add("json")
        if (model.isNotBlank()) { add("-m"); add(model) }
        if (agent.isNotBlank()) { add("--agent"); add(agent) }
        add(message) // single positional arg (no shell → no escaping)
    }

    /**
     * Spawn the subprocess in a neutral working directory (system temp unless overridden). Overridden in
     * tests to inject a controlled subprocess.
     */
    protected open fun spawn(argv: List<String>): Process {
        val dir = workingDir.ifBlank { System.getProperty("java.io.tmpdir") }
        return ProcessBuilder(argv).directory(File(dir)).start()
    }

    // --- the runaway-proof plumbing, mirroring ProcessLlmClient (one bounded loop, force-kill, bounded joins) ---

    private fun awaitProcess(process: Process, timeout: Duration, cancellation: CancellationToken, personaName: String) {
        val pollMs = pollMillis.coerceAtLeast(1)
        val timeoutNanos = timeout.toNanos().coerceAtLeast(0)
        val start = System.nanoTime()
        try {
            while (true) {
                if (cancellation.isCancelled) {
                    kill(process)
                    log.atInfo().setMessage("generation for {} cancelled by owner").addArgument(personaName)
                        .event(EV_CANCELLED).addKeyValue("persona", personaName).log()
                    throw LlmException.Cancelled()
                }
                if (process.waitFor(pollMs, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() - start >= timeoutNanos) {
                    kill(process)
                    val timeoutMs = timeout.toMillis()
                    log.atWarn().setMessage("generation for {} timed out after {}ms")
                        .addArgument(personaName).addArgument(timeoutMs)
                        .event(EV_TIMEOUT).addKeyValue("persona", personaName).addKeyValue("timeoutMs", timeoutMs).log()
                    throw LlmException.Timeout()
                }
            }
        } catch (_: InterruptedException) {
            kill(process)
            Thread.currentThread().interrupt()
            throw LlmException.Cancelled()
        }
    }

    private fun kill(process: Process) {
        process.destroyForcibly()
        runCatching { process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun drain(stream: InputStream): FutureTask<String> =
        FutureTask { stream.use { it.readBytes().decodeToString() } }
            .also { Thread(it).apply { isDaemon = true }.start() }

    private fun drainLines(stream: InputStream, onLine: (String) -> Unit): FutureTask<Unit> =
        FutureTask { stream.bufferedReader(Charsets.UTF_8).use { it.lineSequence().forEach(onLine) } }
            .also { Thread(it).apply { isDaemon = true }.start() }

    private fun await(task: FutureTask<String>): String =
        try { task.get(STREAM_GRACE_MILLIS, TimeUnit.MILLISECONDS) } catch (_: Exception) { task.cancel(true); "" }

    private fun awaitDrain(task: FutureTask<Unit>) {
        try { task.get(STREAM_GRACE_MILLIS, TimeUnit.MILLISECONDS) } catch (_: Exception) { task.cancel(true) }
    }
}
