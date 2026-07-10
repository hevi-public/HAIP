package com.aiforum.tier1.infra

import com.aiforum.backup.SqliteBackup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-1: [SqliteBackup] does real SQLite IO (a `VACUUM INTO` snapshot of a live WAL database to disk), so
 * it's pinned against a temp source DB + temp backup dir rather than mocked. Per the bdd-tiered-testing
 * skill, the snapshot is exercised end-to-end: it must (a) appear at the timestamped path the injected
 * Clock dictates, (b) be a valid, queryable SQLite file carrying the source rows, and (c) be trimmed to
 * the retention limit. No LLM seam is involved.
 */
@Tag("tier1")
class SqliteBackupTest {

    private fun sourceJdbc(dbFile: Path): JdbcTemplate {
        // Match the app's WAL + busy-timeout pragmas so the snapshot path is exercised exactly as in prod.
        val url = "jdbc:sqlite:$dbFile?journal_mode=WAL&busy_timeout=5000&foreign_keys=on"
        val jdbc = JdbcTemplate(DriverManagerDataSource(url))
        jdbc.execute("CREATE TABLE note (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        jdbc.update("INSERT INTO note (id, body) VALUES (1, 'first')")
        jdbc.update("INSERT INTO note (id, body) VALUES (2, 'second')")
        return jdbc
    }

    private fun fixedClock(instant: String) =
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    @Test
    fun `writes a queryable snapshot at the timestamped path`(@TempDir tmp: Path) {
        val jdbc = sourceJdbc(tmp.resolve("source.db"))
        val backupDir = tmp.resolve("backup")
        val backup = SqliteBackup(jdbc, fixedClock("2026-06-25T08:09:10Z"), backupDir, keep = 7)

        val dest = backup.backup()

        // (a) the snapshot lands at the UTC-timestamped name the Clock pins.
        assertEquals(backupDir.resolve("aiforum-20260625T080910Z.db"), dest)
        assertTrue(Files.isRegularFile(dest), "the snapshot file must exist")

        // (b) opening the snapshot and querying returns the source rows => it's a valid DB.
        DriverManager.getConnection("jdbc:sqlite:$dest").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT body FROM note ORDER BY id").use { rs ->
                    rs.next(); assertEquals("first", rs.getString("body"))
                    rs.next(); assertEquals("second", rs.getString("body"))
                    assertFalse(rs.next(), "exactly the two source rows survived into the snapshot")
                }
            }
        }
    }

    @Test
    fun `two snapshots in the same clock-second both succeed with distinct filenames`(@TempDir tmp: Path) {
        val jdbc = sourceJdbc(tmp.resolve("source.db"))
        val backupDir = tmp.resolve("backup")
        // Same fixed instant for both calls => the timestamp collides; the uniquifying suffix must keep
        // them distinct (and `VACUUM INTO` must not fail on a pre-existing dest).
        val backup = SqliteBackup(jdbc, fixedClock("2026-06-25T08:09:10Z"), backupDir, keep = 7)

        val first = backup.backup()
        val second = backup.backup()

        assertEquals(backupDir.resolve("aiforum-20260625T080910Z.db"), first)
        assertEquals(backupDir.resolve("aiforum-20260625T080910Z-1.db"), second)
        assertTrue(Files.isRegularFile(first) && Files.isRegularFile(second), "both snapshots exist")
    }

    @Test
    fun `creates the backup directory if it is absent`(@TempDir tmp: Path) {
        val jdbc = sourceJdbc(tmp.resolve("source.db"))
        val backupDir = tmp.resolve("nested/backup")
        assertFalse(Files.exists(backupDir), "precondition: the backup dir does not exist yet")

        SqliteBackup(jdbc, fixedClock("2026-06-25T08:09:10Z"), backupDir, keep = 7).backup()

        assertTrue(Files.isDirectory(backupDir), "the backup dir must be created on demand")
    }

    @Test
    fun `retention deletes the oldest snapshots once the count exceeds keep`(@TempDir tmp: Path) {
        val jdbc = sourceJdbc(tmp.resolve("source.db"))
        val backupDir = tmp.resolve("backup")
        val base = Instant.parse("2026-06-25T00:00:00Z")

        // Take four snapshots one day apart with keep=2; advancing the fixed Clock gives distinct,
        // chronologically-sorted filenames.
        val written = (0..3).map { day ->
            val clock = Clock.fixed(base.plus(Duration.ofDays(day.toLong())), ZoneOffset.UTC)
            SqliteBackup(jdbc, clock, backupDir, keep = 2).backup()
        }

        val remaining = Files.list(backupDir).use { it.sorted().toList() }
        assertEquals(2, remaining.size, "only the newest `keep` snapshots survive")
        // The two oldest are gone; the two newest remain.
        assertFalse(Files.exists(written[0]), "the oldest snapshot is pruned")
        assertFalse(Files.exists(written[1]), "the second-oldest snapshot is pruned")
        assertTrue(Files.exists(written[2]), "the newest snapshots are kept")
        assertTrue(Files.exists(written[3]), "the newest snapshots are kept")
    }

    @Test
    fun `a non-positive keep disables pruning`(@TempDir tmp: Path) {
        val jdbc = sourceJdbc(tmp.resolve("source.db"))
        val backupDir = tmp.resolve("backup")
        val base = Instant.parse("2026-06-25T00:00:00Z")

        (0..2).forEach { day ->
            val clock = Clock.fixed(base.plus(Duration.ofDays(day.toLong())), ZoneOffset.UTC)
            SqliteBackup(jdbc, clock, backupDir, keep = 0).backup()
        }

        val remaining = Files.list(backupDir).use { it.toList() }
        assertEquals(3, remaining.size, "keep<=0 keeps every snapshot (pruning is off)")
    }
}
