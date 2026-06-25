package com.aiforum.backup

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on Spring's `@Scheduled` support, but ONLY where the backup component actually wires — gated on
 * the same `aiforum.backups.enabled` flag and `!test` profile as [SqliteBackup]. Keeping the gate here
 * means the test context never starts a scheduler thread (and `ProfileGuard`'s backups-off rail stays
 * honest: nothing backup-related is alive under test).
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.backups", name = ["enabled"], havingValue = "true")
@EnableScheduling
class SchedulingConfig
