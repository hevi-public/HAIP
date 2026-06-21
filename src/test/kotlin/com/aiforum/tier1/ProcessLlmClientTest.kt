package com.aiforum.tier1

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.ProcessLlmClient
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-1: the genuinely un-fakeable plumbing of [ProcessLlmClient] — stdin delivery, exit-code mapping,
 * the timeout deadline, and cooperative cancellation. We substitute the spawn with a controlled
 * `/bin/sh` subprocess (poll interval 5ms for snappy tests) so this runs hermetically, without the
 * real `claude` binary or any quota. The classification of well-formed output is proven separately in
 * LlmResponseParserTest.
 */
@Tag("tier1")
class ProcessLlmClientTest {

    /** A ProcessLlmClient whose subprocess is a fixed shell script instead of `claude`. */
    private class ShellClient(private val script: String) :
        ProcessLlmClient(command = "claude", defaultModel = "", workingDir = "", rateLimitRetryAfterSeconds = 300, pollMillis = 5) {
        override fun spawn(argv: List<String>): Process =
            ProcessBuilder("/bin/sh", "-c", script).start()
    }

    /**
     * Captures the argv that would be handed to `claude` (so we can assert model selection) while still
     * returning a well-formed success so generate() completes. The configured `defaultModel` is the
     * fallback when the persona doesn't pin one.
     */
    private class CapturingClient(
        defaultModel: String,
        webFetchEnabled: Boolean = false,
        webFetchAllowedDomains: String = "",
    ) :
        ProcessLlmClient(
            command = "claude",
            defaultModel = defaultModel,
            workingDir = "",
            rateLimitRetryAfterSeconds = 300,
            pollMillis = 5,
            webFetchEnabled = webFetchEnabled,
            webFetchAllowedDomains = webFetchAllowedDomains,
        ) {
        var argv: List<String> = emptyList()
        override fun spawn(argv: List<String>): Process {
            this.argv = argv
            return ProcessBuilder("/bin/sh", "-c", "printf '%s' '{\"is_error\":false,\"subtype\":\"success\",\"result\":\"ok\",\"stop_reason\":\"end_turn\"}'").start()
        }
    }

    private fun request(timeout: Duration, personaModel: String = "") = LlmRequest(
        context = PromptContext("you are sol", listOf(ContextComment("sol", "indexes help here"))),
        persona = PersonaRef("sol", "Sol", personaModel),
        timeout = timeout,
    )

    /** The flag value that follows `--model` in argv, or null when the flag is absent. */
    private fun modelArg(argv: List<String>): String? =
        argv.indexOf("--model").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    /** The flag value that follows `--allowedTools` in argv, or null when the flag is absent. */
    private fun allowedToolsArg(argv: List<String>): String? =
        argv.indexOf("--allowedTools").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    @Test
    fun `a persona's pinned model is passed as --model and wins over the configured default`() {
        val client = CapturingClient(defaultModel = "sonnet")
        client.generate(request(Duration.ofSeconds(10), personaModel = "opus"), CancellationToken())
        assertEquals("opus", modelArg(client.argv))
    }

    @Test
    fun `a persona with no pinned model falls back to the configured default-model`() {
        val client = CapturingClient(defaultModel = "sonnet")
        client.generate(request(Duration.ofSeconds(10), personaModel = ""), CancellationToken())
        assertEquals("sonnet", modelArg(client.argv))
    }

    @Test
    fun `with neither a persona model nor a default, no --model flag is sent and the CLI picks its own`() {
        val client = CapturingClient(defaultModel = "")
        client.generate(request(Duration.ofSeconds(10), personaModel = ""), CancellationToken())
        assertEquals(null, modelArg(client.argv))
    }

    @Test
    fun `with web-fetch disabled no --allowedTools flag is sent so headless mode keeps WebFetch denied`() {
        val client = CapturingClient(defaultModel = "", webFetchEnabled = false)
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals(null, allowedToolsArg(client.argv))
    }

    @Test
    fun `web-fetch enabled with no domain allowlist pre-authorises bare WebFetch for any host`() {
        val client = CapturingClient(defaultModel = "", webFetchEnabled = true)
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("WebFetch", allowedToolsArg(client.argv))
    }

    @Test
    fun `web-fetch enabled with a domain allowlist scopes WebFetch to one rule per host`() {
        val client = CapturingClient(
            defaultModel = "",
            webFetchEnabled = true,
            webFetchAllowedDomains = "news.ycombinator.com, github.com",
        )
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("WebFetch(domain:news.ycombinator.com),WebFetch(domain:github.com)", allowedToolsArg(client.argv))
    }

    @Test
    fun `the rendered prompt is delivered on stdin and the parsed result comes back`() {
        // The script proves stdin arrived: it echoes a different result when stdin is non-empty.
        val script = """
            in=${'$'}(cat)
            if [ -n "${'$'}in" ]; then
              printf '%s' '{"is_error":false,"subtype":"success","result":"got-prompt","stop_reason":"end_turn"}'
            else
              printf '%s' '{"is_error":false,"subtype":"success","result":"no-prompt","stop_reason":"end_turn"}'
            fi
        """.trimIndent()
        val resp = ShellClient(script).generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("got-prompt", resp.text)
    }

    @Test
    fun `a non-zero exit maps to ProcessError carrying the code`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            ShellClient("echo boom >&2; exit 7").generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
        assertEquals(7, ex.exitCode)
    }

    @Test
    fun `a subprocess that outruns the timeout is killed and surfaces Timeout`() {
        assertThrows(LlmException.Timeout::class.java) {
            ShellClient("sleep 5").generate(request(Duration.ofMillis(150)), CancellationToken())
        }
    }

    @Test
    fun `tripping the cancellation token kills the subprocess and surfaces Cancelled`() {
        val token = CancellationToken()
        Thread { Thread.sleep(50); token.cancel() }.apply { isDaemon = true }.start()
        assertThrows(LlmException.Cancelled::class.java) {
            ShellClient("sleep 5").generate(request(Duration.ofSeconds(30)), token)
        }
    }
}
