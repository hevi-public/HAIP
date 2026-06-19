package com.aiforum.tier0

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0 placeholder: pure, no Spring, no mocks. Exists to prove the JUnit-6 + tag-filtered
 * `tier0` Gradle task and the JDK-21 toolchain are wired. Replaced by real pure-function tests
 * (DepthBudget, ContextAssembler, GenerationStateMachine) in Phase C.
 */
@Tag("tier0")
class SanityTest {
    @Test
    fun `toolchain and tiered test wiring works`() {
        assertEquals(4, 2 + 2)
    }
}
