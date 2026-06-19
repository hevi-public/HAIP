package com.aiforum.llm

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Production/dev LLM client. M1 STUB: the real implementation wraps `claude -p` via ProcessBuilder
 * inside the Docker jail, with a bounded timeout and cancellation mapped to process.destroyForcibly().
 * Deferred to the implementing team — the acceptance suite pins the contract this must satisfy.
 *
 * Under the `test` profile a @Primary ScriptableLlmClient replaces this, so it is never exercised by
 * the acceptance tests.
 */
@Component
@Profile("!test")
class ProcessLlmClient : LlmClient {
    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse =
        throw NotImplementedError("ProcessLlmClient is an M1 stub; real `claude -p` wiring is deferred")
}
