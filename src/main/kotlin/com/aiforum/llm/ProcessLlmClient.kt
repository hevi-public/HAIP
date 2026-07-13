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
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Production/dev [LlmClient]: wraps `claude -p --output-format json` via [ProcessBuilder], honouring a
 * bounded timeout and cooperative cancellation. It is the single Tier-1 IO seam (see the
 * bdd-tiered-testing skill) — the pure classification of its result lives in [LlmResponseParser], so
 * everything here is the genuinely un-fakeable part: spawning, stdin/stdout plumbing, the deadline.
 *
 * Under the `test` profile a @Primary ScriptableLlmClient replaces this, so the acceptance suite never
 * shells out. The Docker jail (§10–§12) and tool-sandboxing are deferred — M1 wraps the CLI directly.
 *
 * `open` so the Tier-1 test can substitute [spawn] with a controlled subprocess and exercise the
 * timeout/cancel/exit-code plumbing without invoking the real `claude` binary or spending quota.
 */
@Component
@Profile("!test")
// Exactly one LlmClient loads, chosen by `aiforum.llm.provider`: cli (this, the default) vs openai
// ([OpenAiLlmClient]) vs stub ([StubLlmClient], canned replies for demos/UI work). matchIfMissing keeps
// `claude -p` the default when the key is absent.
@ConditionalOnProperty(prefix = "aiforum.llm", name = ["provider"], havingValue = "cli", matchIfMissing = true)
open class ProcessLlmClient(
    @Value("\${aiforum.llm.command:claude}") private val command: String,
    // `defaultModel` (not `model`) because the model is persona-specific (PersonaRef.model) — this is the
    // fallback used when the persona doesn't pin one. Blank => the CLI's own default model.
    @Value("\${aiforum.llm.default-model:}") private val defaultModel: String,
    @Value("\${aiforum.llm.working-dir:}") private val workingDir: String,
    @Value("\${aiforum.llm.rate-limit-retry-after-seconds:300}") private val rateLimitRetryAfterSeconds: Long,
    @Value("\${aiforum.llm.poll-millis:100}") private val pollMillis: Long,
    // Headless `claude -p` cannot prompt for tool permission, so any tool that needs approval (WebFetch
    // among them) is silently denied — a persona asked to check the web just reports it can't reach the
    // network. These pre-authorise WebFetch for the spawned CLI. Off by default; toggled per-profile via
    // application-{dev,prod}.yml. Kotlin defaults so direct (test) construction needn't pass them; Spring
    // still injects the @Value either way.
    // ⚠ SECURITY: enabling this lets personas fetch the open web from the host — the Docker jail
    // (requirements §12) that should isolate this is not built yet, so the domain allowlist is the only
    // mitigation today and web content is untrusted input (prompt injection). See requirements §12.
    @Value("\${aiforum.llm.web-fetch-enabled:false}") private val webFetchEnabled: Boolean = false,
    @Value("\${aiforum.llm.web-fetch-allowed-domains:}") private val webFetchAllowedDomains: String = "",
    // GitHub read-only tools for personas (Option B): when enabled, the spawned `claude -p` is handed the
    // gh-readonly MCP server via --mcp-config and its tools are pre-authorised — headless mode can't prompt
    // for tool permission, exactly like WebFetch above, so an un-authorised tool is silently denied.
    // cli-provider only (the OpenAI path has no tool loop). Needs `gh` installed + authenticated on the host.
    // ⚠ SECURITY: GitHub content (issue/PR/comment bodies) is UNTRUSTED input — prompt injection, the same
    // risk class as WebFetch — and the Docker jail (requirements §12) that should isolate the spawned CLI
    // from the host isn't built yet. The MCP server is read-only, so a persona can never mutate the repo;
    // but it can read whatever the host's `gh` auth can see, so scope that auth deliberately. Off by default.
    @Value("\${aiforum.llm.github-tools-enabled:false}") private val githubToolsEnabled: Boolean = false,
    // Passed straight to `claude --mcp-config` (a file path or inline JSON). Typically an absolute path to
    // the repo's .mcp.json. Blank => the feature is inert even if the flag above is on (we never guess a path).
    @Value("\${aiforum.llm.github-mcp-config:}") private val githubMcpConfig: String = "",
    // Must match the server key inside that config; authorises `mcp__<name>` (all of the server's read tools).
    @Value("\${aiforum.llm.github-mcp-server-name:gh-readonly}") private val githubMcpServerName: String = "gh-readonly",
    // Streaming only: pass `--include-partial-messages` so claude emits token-level content_block_delta
    // events (not just whole assistant messages) for live typing. Default on; flip off if a CLI version
    // doesn't support the flag — the stream then degrades to whole-message granularity, which
    // [ClaudeStreamParser] still handles. The non-streaming generate() path ignores this.
    @Value("\${aiforum.llm.stream-partial-messages:true}") private val streamPartialMessages: Boolean = true,
) : LlmClient {

    // Explicit class (not javaClass) so the logger name is stable across the test subclasses that override
    // spawn() — the log output is a tested contract (see the bdd-tiered-testing skill, "Logging is IO").
    private val log = LoggerFactory.getLogger(ProcessLlmClient::class.java)

    private companion object {
        /** Grace after destroyForcibly() to reap the process, so we never return with a SIGKILL in flight. */
        const val KILL_GRACE_MILLIS = 500L
        /** Once the process has exited, its pipes are at EOF; this bounds the reader join so no path can hang. */
        const val STREAM_GRACE_MILLIS = 2_000L

        // Structured log event ids for the generation seam (the `llm.*` namespace — see LogEvents / the
        // bdd-tiered-testing skill). The id is the contract; reword the message freely.
        const val EV_SPAWN = "llm.spawn"
        const val EV_TIMEOUT = "llm.timeout"
        const val EV_CANCELLED = "llm.cancelled"
    }

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        logSpawn(request)
        val process = spawn(buildArgs(request.context.personaSystemPrompt, request.persona.model, stream = false))
        writeStdin(process, request)

        // Drain both pipes on daemon threads so a chatty subprocess can't deadlock on a full OS buffer
        // while we sit in waitFor.
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)

        awaitProcess(process, request.timeout, cancellation, request.persona.name)

        await(stderr) // let the reader finish so the pipe closes cleanly; stderr isn't part of the mapping
        return LlmResponseParser.parse(
            process.exitValue(),
            await(stdout),
            Duration.ofSeconds(rateLimitRetryAfterSeconds),
        )
    }

    /**
     * Streaming variant: spawn with `--output-format stream-json` and read stdout line by line, handing each
     * NDJSON line to [ClaudeStreamParser] so token deltas + tool-call status reach [sink] live. The captured
     * terminal `result` line still goes through [LlmResponseParser], so the returned (and persisted)
     * [LlmResponse] is identical to the non-streaming path — the deltas are purely for liveness. Reuses the
     * SAME runaway-proof [awaitProcess] loop, so timeout/cancel/exit-code behaviour can't drift between modes.
     */
    override fun generate(request: LlmRequest, cancellation: CancellationToken, sink: AguiEventSink): LlmResponse {
        sink.emit(AguiEvent.RunStarted(request.runId))
        try {
            logSpawn(request)
            val process = spawn(buildArgs(request.context.personaSystemPrompt, request.persona.model, stream = true))
            writeStdin(process, request)

            val parser = ClaudeStreamParser(request.runId)
            // Reading lines IS the drain here — the callback runs per line as it arrives, so deltas reach
            // the sink while the model is still typing.
            val stdout = drainLines(process.inputStream) { line -> parser.onLine(line).forEach(sink::emit) }
            val stderr = drain(process.errorStream)

            awaitProcess(process, request.timeout, cancellation, request.persona.name)
            await(stderr)
            awaitDrain(stdout) // barrier: ensure the tail (incl. the result line) is read before we classify

            val response = LlmResponseParser.parse(
                process.exitValue(),
                parser.resultJson,
                Duration.ofSeconds(rateLimitRetryAfterSeconds),
            )
            sink.emit(AguiEvent.RunFinished(request.runId))
            return response
        } catch (e: Throwable) {
            sink.emit(AguiEvent.RunError(request.runId, e.message ?: "generation failed"))
            throw e
        }
    }

    /** Log the spawn with the model actually selected (persona pin > configured default > CLI default). */
    private fun logSpawn(request: LlmRequest) {
        val model = request.persona.model.ifBlank { defaultModel }.ifBlank { "(cli default)" }
        log.atDebug().setMessage("spawning {} for persona {} (model {})")
            .addArgument(command).addArgument(request.persona.name).addArgument(model)
            .event(EV_SPAWN).addKeyValue("persona", request.persona.name).addKeyValue("model", model)
            .log()
    }

    /**
     * Feed the prompt on stdin (the CLI reads it there in -p mode) then close. A broken pipe means the
     * process already died; the parser surfaces that from the exit code, so we don't fail here.
     */
    private fun writeStdin(process: Process, request: LlmRequest) {
        try {
            process.outputStream.use {
                it.write(PromptRenderer.renderTask(request.context, request.persona.name).toByteArray())
            }
        } catch (_: IOException) {
            // process exited before consuming stdin — classification handles it
        }
    }

    /**
     * Wait for the subprocess, bounded and runaway-proof — important on a remote box where a hung child
     * can't be killed by hand. Each iteration blocks at most `pollMs` in waitFor; the only exits are the
     * process finishing, the token tripping, or the monotonic deadline firing (nanoTime subtraction is
     * wraparound-safe; the poll interval floors at 1ms so a misconfigured 0 can't busy-spin). Shared by
     * the streaming and non-streaming generate paths so their lifecycle behaviour stays identical.
     */
    private fun awaitProcess(process: Process, timeout: Duration, cancellation: CancellationToken, personaName: String) {
        val pollMs = pollMillis.coerceAtLeast(1)
        val timeoutNanos = timeout.toNanos().coerceAtLeast(0)
        val start = System.nanoTime()
        try {
            while (true) {
                if (cancellation.isCancelled) {
                    kill(process)
                    log.atInfo().setMessage("generation for {} cancelled by owner").addArgument(personaName)
                        .event(EV_CANCELLED).addKeyValue("persona", personaName)
                        .log()
                    throw LlmException.Cancelled()
                }
                if (process.waitFor(pollMs, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() - start >= timeoutNanos) {
                    kill(process)
                    val timeoutMs = timeout.toMillis()
                    log.atWarn().setMessage("generation for {} timed out after {}ms")
                        .addArgument(personaName).addArgument(timeoutMs)
                        .event(EV_TIMEOUT).addKeyValue("persona", personaName).addKeyValue("timeoutMs", timeoutMs)
                        .log()
                    throw LlmException.Timeout()
                }
            }
        } catch (_: InterruptedException) {
            kill(process)
            Thread.currentThread().interrupt()
            throw LlmException.Cancelled()
        }
    }

    private fun buildArgs(systemPrompt: String, personaModel: String, stream: Boolean): List<String> = buildList {
        add(command)
        add("-p")
        // stream-json emits NDJSON (a final `result` line plus, with partial messages, token deltas); the
        // CLI requires --verbose alongside it in -p mode. Plain json is the single result envelope.
        add("--output-format"); add(if (stream) "stream-json" else "json")
        if (stream) {
            add("--verbose")
            if (streamPartialMessages) add("--include-partial-messages")
        }
        add("--system-prompt"); add(systemPrompt)
        // The persona's pinned model wins; a blank one falls back to the configured default; both blank
        // => no --model flag, so the CLI picks its own default.
        val model = personaModel.ifBlank { defaultModel }
        if (model.isNotBlank()) {
            add("--model"); add(model)
        }
        // Read-only GitHub tools (Option B): mount the gh-readonly MCP server for this invocation.
        // --strict-mcp-config keeps it hermetic — only this config is loaded, never an ambient ~/.claude one.
        if (githubToolsActive()) {
            add("--mcp-config"); add(githubMcpConfig)
            add("--strict-mcp-config")
        }
        // Pre-authorise tools that headless mode would otherwise deny. `--allowedTools` takes a
        // comma-separated list of permission rules; an empty list means we send no flag at all.
        val allowed = allowedTools()
        if (allowed.isNotEmpty()) {
            add("--allowedTools"); add(allowed.joinToString(","))
        }
    }

    /** The GitHub tool seam is only mounted when explicitly enabled AND given a config (we never guess a
     *  path); a bare flag with no config stays inert. */
    private fun githubToolsActive(): Boolean = githubToolsEnabled && githubMcpConfig.isNotBlank()

    /**
     * Permission rules to pass through `--allowedTools`. WebFetch is gated by [webFetchEnabled]: a blank
     * domain list grants bare `WebFetch` (any host), otherwise one scoped `WebFetch(domain:<host>)` rule
     * per configured host, so personas can only reach the allowlist.
     */
    private fun allowedTools(): List<String> = buildList {
        if (webFetchEnabled) {
            val domains = webFetchAllowedDomains.split(",").map(String::trim).filter(String::isNotEmpty)
            if (domains.isEmpty()) add("WebFetch") else domains.forEach { add("WebFetch(domain:$it)") }
        }
        // `mcp__<server>` authorises every tool the gh-readonly server exposes — all read-only by design.
        if (githubToolsActive()) add("mcp__$githubMcpServerName")
    }

    /**
     * Spawn the subprocess. Defaults to a real [ProcessBuilder] rooted in a neutral working directory
     * (the system temp dir unless overridden) so the project's own CLAUDE.md doesn't leak into the
     * persona's context. Overridden in tests to inject a controlled subprocess.
     */
    protected open fun spawn(argv: List<String>): Process {
        val dir = workingDir.ifBlank { System.getProperty("java.io.tmpdir") }
        return ProcessBuilder(argv).directory(File(dir)).start()
    }

    /** Force-kill and best-effort reap within a short grace, so a runaway child can't outlive the call. */
    private fun kill(process: Process) {
        process.destroyForcibly()
        runCatching { process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    private fun drain(stream: InputStream): FutureTask<String> =
        FutureTask { stream.use { it.readBytes().decodeToString() } }
            .also { Thread(it).apply { isDaemon = true }.start() }

    /**
     * Like [drain], but invokes [onLine] for each line AS it arrives (lineSequence reads until the pipe
     * EOFs when the process exits) — this is how stream-json deltas reach the sink live. The callback runs
     * on the daemon reader thread.
     */
    private fun drainLines(stream: InputStream, onLine: (String) -> Unit): FutureTask<Unit> =
        FutureTask { stream.bufferedReader(Charsets.UTF_8).use { it.lineSequence().forEach(onLine) } }
            .also { Thread(it).apply { isDaemon = true }.start() }

    /** Bounded join on a line-drain task — the process has exited, so this returns promptly; the grace is a
     *  backstop that abandons a stuck reader rather than hanging. */
    private fun awaitDrain(task: FutureTask<Unit>) {
        try {
            task.get(STREAM_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
        }
    }

    /** Bounded join on a drain task: the process has already exited, so this returns promptly; the grace
     *  is a backstop that turns a stuck reader into empty output rather than a hang. */
    private fun await(task: FutureTask<String>): String =
        try {
            task.get(STREAM_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            ""
        }
}
