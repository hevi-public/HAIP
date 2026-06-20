package com.aiforum.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock

/**
 * The Clock is an injected IO-ish dependency (see the bdd-tiered-testing skill): production code
 * never calls Instant.now() directly, so tests can fix time. The `test` profile supplies a fixed
 * Clock instead (in the test config), which is what makes Retry-After and timestamps deterministic.
 */
@Configuration
class ClockConfig {
    @Bean
    @Profile("!test")
    fun systemClock(): Clock = Clock.systemUTC()
}
