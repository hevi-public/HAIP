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
    @Value("\${aiforum.llm.model:}") private val model: String,
    @Value("\${aiforum.llm.working-dir:}") private val workingDir: String,
    @Value("\${aiforum.llm.rate-limit-retry-after-seconds:300}") private val rateLimitRetryAfterSeconds: Long,
    @Value("\${aiforum.llm.poll-millis:100}") private val pollMillis: Long,
) : LlmClient {

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        val process = spawn(buildArgs(request.context.personaSystemPrompt))

        // Feed the prompt on stdin (the CLI reads it there in -p mode) then close. A broken pipe means
        // the process already died; the parser surfaces that from the exit code, so we don't fail here.
        try {
            process.outputStream.use { it.write(renderPrompt(request.context).toByteArray()) }
        } catch (_: IOException) {
            // process exited before consuming stdin — classification below handles it
        }

        // Drain both pipes on daemon threads so a chatty subprocess can't deadlock on a full OS buffer
        // while we sit in waitFor.
        val stdout = drain(process.inputStream)
        val stderr = drain(process.errorStream)

        val deadlineNanos = System.nanoTime() + request.timeout.toNanos()
        try {
            while (true) {
                if (cancellation.isCancelled) {
                    process.destroyForcibly()
                    throw LlmException.Cancelled()
                }
                if (process.waitFor(pollMillis, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadlineNanos) {
                    process.destroyForcibly()
                    throw LlmException.Timeout()
                }
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw LlmException.Cancelled()
        }

        stderr.get() // let the reader finish so the pipe closes cleanly; stderr isn't part of the mapping
        return LlmResponseParser.parse(
            process.exitValue(),
            stdout.get(),
            Duration.ofSeconds(rateLimitRetryAfterSeconds),
        )
    }

    private fun buildArgs(systemPrompt: String): List<String> = buildList {
        add(command)
        add("-p")
        add("--output-format"); add("json")
        add("--system-prompt"); add(systemPrompt)
        if (model.isNotBlank()) {
            add("--model"); add(model)
        }
    }

    private fun renderPrompt(context: PromptContext): String {
        val transcript = context.comments.joinToString("\n\n") { "${it.authorId}: ${it.body}" }
        return if (transcript.isBlank()) {
            "Open the discussion with a first reply, in character."
        } else {
            "$transcript\n\n---\nWrite your next reply to this discussion, in character. " +
                "Respond with the reply text only."
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

    private fun drain(stream: InputStream): FutureTask<String> =
        FutureTask { stream.use { it.readBytes().decodeToString() } }
            .also { Thread(it).apply { isDaemon = true }.start() }
}
