package com.aiforum.tier1

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Tier-1: proves Flyway actually *upgrades an existing older database forward*, not just builds an empty
 * one from scratch (which is all the from-scratch context-load boot exercises). We migrate a temp DB to
 * an intermediate version (V3 — before persona.model/slug/color_index existed), seed rows on that old
 * schema, then migrate to latest and assert the old data survived and the new columns picked up their
 * migration DEFAULT / backfill. This is the guarantee long-term prod data depends on every schema bump.
 */
@Tag("tier1")
class MigrationPipelineTest {

    private fun flyway(url: String, target: String?) =
        Flyway.configure()
            .dataSource(url, null, null)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
            .load()

    @Test
    fun `migrates an older database forward, preserving data and backfilling new columns`(@TempDir tmp: Path) {
        val url = "jdbc:sqlite:${tmp.resolve("aiforum.db")}"

        // 1. Bring the DB up to the intermediate V3 schema only.
        flyway(url, "3").migrate()

        // 2. Seed two persona rows against the OLD (pre-V4/V5/V6) schema — no model/slug/color columns yet.
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO persona (id, name, handle, system_prompt) VALUES " +
                        "('Ada', 'Ada', 'ada', 'You are Ada.'), " +
                        "('Bob', 'Bob', 'bob', 'You are Bob.')",
                )
            }
        }

        // 3. Upgrade the EXISTING db to the latest schema (Flyway applies only the pending V4–V6).
        flyway(url, null).migrate()

        // 4. The old rows survived, and the new columns carry their migration default / backfill.
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, model, slug, color_index FROM persona ORDER BY rowid").use { rs ->
                    rs.next()
                    assertEquals("Ada", rs.getString("id"), "the pre-existing row must survive the upgrade")
                    assertEquals("", rs.getString("model"), "V4 DEFAULT '' applies to the pre-existing row")
                    assertEquals("", rs.getString("slug"), "V5 DEFAULT '' applies to the pre-existing row")
                    assertEquals(0, rs.getInt("color_index"), "V6 backfills colour slots in rowid order")
                    rs.next()
                    assertEquals("Bob", rs.getString("id"))
                    assertEquals(1, rs.getInt("color_index"), "the second row gets the next colour slot")
                }

                // flyway_schema_history records the full V1..V7 chain as applied.
                st.executeQuery("SELECT MAX(CAST(version AS INTEGER)) AS v FROM flyway_schema_history").use { rs ->
                    rs.next()
                    assertEquals(7, rs.getInt("v"), "all seven migrations should be recorded as applied")
                }
            }
        }
    }
}
