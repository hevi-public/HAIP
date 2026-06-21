package com.aiforum.config

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Repair-then-migrate on startup.
 *
 * V7 ("thread body") shipped in two forms: a short-lived nullable `ADD COLUMN body TEXT`, then the
 * canonical `... NOT NULL DEFAULT ''`. Databases that applied the nullable form recorded V7 with a
 * different checksum than the file now resolves to, so Flyway's validate-on-migrate aborts at startup
 * with a checksum mismatch — BEFORE any pending migration (e.g. V8's backfill) can run. A checksum
 * mismatch can never be healed by a forward migration; only [Flyway.repair] can.
 *
 * [Flyway.repair] realigns the schema-history checksums to the current migration files (it does NOT
 * re-run or re-apply any migration, and does NOT touch table data); [Flyway.migrate] then applies the
 * pending migrations. Both are no-ops on an already-consistent database, so this is safe on fresh
 * installs and CI as well as on the affected production DB.
 *
 * Trade-off: this gives up validate-on-migrate's protection against an applied migration's body being
 * edited by mistake. It exists to heal the one-off V7 split; once every live database has booted past
 * V8, this bean can be removed and default validate-then-migrate behaviour restored.
 */
@Configuration
class FlywayConfig {
    @Bean
    fun repairThenMigrate() = FlywayMigrationStrategy { flyway ->
        flyway.repair()
        flyway.migrate()
    }
}
