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

    private fun request(timeout: Duration) = LlmRequest(
        context = PromptContext("you are sol", listOf(ContextComment("sol", "indexes help here"))),
        persona = PersonaRef("sol", "Sol"),
        timeout = timeout,
    )

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
