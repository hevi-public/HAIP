package com.aiforum

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Boots the whole Spring context under the `test` profile — exercising Flyway against SQLite,
 * the datasource wiring, and ProfileGuard. Proves the 2026 stack actually starts, not just compiles.
 */
@Tag("tier2")
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadsTest {
    @Test
    fun `application context loads under the test profile`() {
        // success == the context started: Flyway migrated, datasource wired, ProfileGuard passed
    }
}
