package com.aiforum.llm

import org.springframework.beans.factory.annotation.Value
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
) : LlmClient {

    private companion object {
        /** Grace after destroyForcibly() to reap the process, so we never return with a SIGKILL in flight. */
        const val KILL_GRACE_MILLIS = 500L
        /** Once the process has exited, its pipes are at EOF; this bounds the reader join so no path can hang. */
        const val STREAM_GRACE_MILLIS = 2_000L

        /**
         * Length discipline appended to every render. Personas were defaulting to essay-length replies
         * (a multi-paragraph wall with bullet lists for a one-line point), which makes a threaded forum
         * unreadable. This asks for variety with a *concise* default — short by default, longer only when
         * the substance earns it — without hard-capping, so a genuinely meaty reply can still breathe.
         * It lives in the task prompt (not the stored per-persona system prompt) so it applies to every
         * persona immediately, including ones already seeded into the DB.
         */
        const val BREVITY =
            "Keep it concise and conversational, the way you'd actually talk in a thread: match the " +
                "length to what you genuinely have to say. Most replies are a sentence or two; reach " +
                "for a short paragraph only when the point really needs it, and avoid long bullet " +
                "lists or essays. Don't restate the question or pad — make your point and stop."
    }

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        val process = spawn(buildArgs(request.context.personaSystemPrompt, request.persona.model))

        // Feed the prompt on stdin (the CLI reads it there in -p mode) then close. A broken pipe means
        // the process already died; the parser surfaces that from the exit code, so we don't fail here.
        try {
            process.outputStream.use { it.write(renderPrompt(request.context, request.persona.name).toByteArray()) }
        } catch (_: IOException) {
            // process exited before consuming stdin — classification below handles it
        }

        // Drain both pipes on daemon threads so a chatty subprocess can't deadlock on a full OS buffer
        // while we sit in waitFor.
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)

        // Termination is bounded and runaway-proof — important because this runs on a remote box where a
        // hung subprocess can't be killed by hand. Each iteration blocks at most `pollMs` in waitFor, and
        // there is no exit other than: the process finishing, the token tripping, or the monotonic
        // deadline firing. The elapsed check uses nanoTime subtraction (wraparound-safe) and the poll
        // interval is floored at 1ms so a misconfigured 0 can't turn this into a busy-spin.
        val pollMs = pollMillis.coerceAtLeast(1)
        val timeoutNanos = request.timeout.toNanos().coerceAtLeast(0)
        val start = System.nanoTime()
        try {
            while (true) {
                if (cancellation.isCancelled) {
                    kill(process)
                    throw LlmException.Cancelled()
                }
                if (process.waitFor(pollMs, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() - start >= timeoutNanos) {
                    kill(process)
                    throw LlmException.Timeout()
                }
            }
        } catch (_: InterruptedException) {
            kill(process)
            Thread.currentThread().interrupt()
            throw LlmException.Cancelled()
        }

        await(stderr) // let the reader finish so the pipe closes cleanly; stderr isn't part of the mapping
        return LlmResponseParser.parse(
            process.exitValue(),
            await(stdout),
            Duration.ofSeconds(rateLimitRetryAfterSeconds),
        )
    }

    private fun buildArgs(systemPrompt: String, personaModel: String): List<String> = buildList {
        add(command)
        add("-p")
        add("--output-format"); add("json")
        add("--system-prompt"); add(systemPrompt)
        // The persona's pinned model wins; a blank one falls back to the configured default; both blank
        // => no --model flag, so the CLI picks its own default.
        val model = personaModel.ifBlank { defaultModel }
        if (model.isNotBlank()) {
            add("--model"); add(model)
        }
        // Pre-authorise tools that headless mode would otherwise deny. `--allowedTools` takes a
        // comma-separated list of permission rules; an empty list means we send no flag at all.
        val allowed = allowedTools()
        if (allowed.isNotEmpty()) {
            add("--allowedTools"); add(allowed.joinToString(","))
        }
    }

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
    }

    private fun renderPrompt(context: PromptContext, personaName: String): String {
        val transcript = renderTranscript(context.comments)
        // Name the persona explicitly and point it at the most recent message: without this the model
        // sees its own past lines labelled "$name:" amid the transcript and gets meta about who it is
        // ("the framing got flipped"). The system prompt carries the character; this carries the task.
        return if (transcript.isBlank()) {
            "You are opening a new thread in the forum. Post the first message as $personaName, in " +
                "character. $BREVITY"
        } else {
            "The forum discussion so far. Each line is \"[#ref] author: message\"; indentation shows " +
                "reply depth and \"↳ replying to #n\" marks which message it answers:\n\n$transcript\n\n---\n" +
                "Write ${personaName}'s next reply, responding to the most recent message above. " +
                "Reply with the message text only, in character as $personaName. $BREVITY"
        }
    }

    /**
     * Flat transcript carrying thread shape. Indentation (depth, normalised to the shallowest comment
     * in scope so branch-only paths don't run off the page) gives the model an at-a-glance picture; the
     * "↳ replying to #n" tag is the load-bearing, whitespace-independent signal — it survives even if a
     * future CLI trims leading space. Bodies are flattened to one line so the grid stays legible.
     */
    private fun renderTranscript(comments: List<ContextComment>): String {
        // Stable short ref per comment, in transcript order, so reply tags disambiguate repeated authors.
        val refOf = comments.withIndex().associate { (i, c) -> c.id to (i + 1) }
        val base = comments.minOfOrNull { it.depth } ?: 0
        return comments.joinToString("\n") { c ->
            val indent = "  ".repeat((c.depth - base).coerceAtLeast(0))
            val ref = refOf.getValue(c.id)
            val replyTag = c.parentId?.let { refOf[it] }?.let { " ↳ replying to #$it" } ?: ""
            val body = c.body.replace("\n", " ").trim()
            "$indent[#$ref$replyTag] ${c.authorId}: $body"
        }
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
