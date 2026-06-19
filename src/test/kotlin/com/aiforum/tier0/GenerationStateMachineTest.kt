package com.aiforum.tier0

import com.aiforum.domain.lifecycle.GenerationStateMachine
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.llm.LlmException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/** Tier-0: the pure failure → UX-state mapping (§4). No Spring, no mocks. */
@Tag("tier0")
class GenerationStateMachineTest {

    @Test
    fun `timeout is a retryable generation error`() {
        val o = GenerationStateMachine.classify(LlmException.Timeout())
        assertEquals(GenerationState.FAILED, o.state)
        assertEquals(FailureCategory.FAILED_RETRY, o.failureCategory)
        assertTrue(o.retryable)
        assertNull(o.retryAfterSeconds)
    }

    @Test
    fun `rate-limit is a distinct state carrying retry-after`() {
        val o = GenerationStateMachine.classify(LlmException.RateLimited(Duration.ofMinutes(7)))
        assertEquals(FailureCategory.RATE_LIMITED, o.failureCategory)
        assertEquals(420L, o.retryAfterSeconds)
    }

    @Test
    fun `owner cancel is cancelled, not an error`() {
        val o = GenerationStateMachine.classify(LlmException.Cancelled())
        assertEquals(GenerationState.CANCELLED, o.state)
        assertEquals(FailureCategory.CANCELLED, o.failureCategory)
    }

    @Test
    fun `process error reports the exit code`() {
        val o = GenerationStateMachine.classify(LlmException.ProcessError(1))
        assertEquals(FailureCategory.FAILED_RETRY, o.failureCategory)
        assertTrue(o.reason.contains("1"))
    }
}
