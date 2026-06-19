package com.aiforum.acceptance

import com.aiforum.llm.LlmException
import io.cucumber.java.ParameterType
import java.time.Duration

/**
 * Maps Gherkin failure words to an exception factory so the sad-path Scenario Outline reads cleanly
 * (see the cucumber-spring-bdd skill). The expected UX state/category for each is asserted in the
 * feature's Examples table, not hard-coded here.
 */
data class FailureMode(val word: String, val makeException: () -> RuntimeException)

class ParameterTypes {
    @ParameterType("timeout|process error|empty output|malformed|rate-limit")
    fun failureMode(word: String): FailureMode = FailureMode(word) {
        when (word) {
            "timeout" -> LlmException.Timeout()
            "process error" -> LlmException.ProcessError(1)
            "empty output" -> LlmException.EmptyOutput()
            "malformed" -> LlmException.MalformedOutput("…truncated")
            "rate-limit" -> LlmException.RateLimited(Duration.ofMinutes(7))
            else -> error("unknown failure mode: $word")
        }
    }
}
