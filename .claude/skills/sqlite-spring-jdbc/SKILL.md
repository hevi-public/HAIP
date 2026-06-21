---
name: sqlite-spring-jdbc
description: SQLite persistence for the AI Forum Spring Boot + Kotlin app using spring-jdbc (JdbcTemplate), Flyway, and recursive CTEs — deliberately NOT Hibernate. Use this whenever working with the database layer — datasource config, profile isolation (prod/dev/test), the xerial sqlite-jdbc + WAL/busy-timeout setup, Flyway migrations on SQLite, writing the recursive-CTE branch/ancestor/subtree queries for the comment tree, JdbcTemplate RowMappers, or per-scenario test-DB reset. Reach for it before adding repositories, migrations, or datasource beans so the tree queries and profile guardrails stay correct.
---

# SQLite + spring-jdbc for AI Forum

Storage is SQLite via `JdbcTemplate` + Flyway. We deliberately avoid Hibernate: it has no maintained
official SQLite dialect (community ones rot across Hibernate majors), it fights SQLite's dynamic
typing, and the core mechanism here — recursive-CTE branch retrieval (§5/§11) — is hand-written SQL
anyway, so an ORM buys nothing. `JdbcTemplate` gives full control of the CTE, deterministic SQL,
trivial fixture seeding, and zero dialect risk.

This is the persistence seam of [[bdd-tiered-testing]]; acceptance tests run against the **real** test
DB (see [[cucumber-spring-bdd]] for the reset hooks).

## Dependencies

```kotlin
implementation("org.springframework.boot:spring-boot-starter-jdbc")
implementation("org.xerial:sqlite-jdbc:3.53.2.0")
// Spring Boot 4 MODULARISED autoconfig: the starter brings flyway-core AND the spring-boot-flyway
// autoconfiguration module. Adding flyway-core alone leaves Flyway un-autoconfigured — it silently
// never runs, your tables are never created, and the only symptom is "no such table" at query time.
implementation("org.springframework.boot:spring-boot-starter-flyway")
implementation("org.flywaydb:flyway-database-nc-sqlite:12.4.0")     // ← the real SQLite module name
```

Two easy-to-get-wrong things here, both verified the hard way:
- **Use the `spring-boot-starter-flyway`**, not bare `flyway-core`. In Spring Boot 4 autoconfig is split
  into per-tech modules (`spring-boot-flyway`, `spring-boot-jdbc`, …); the starters pull the matching
  autoconfig module. A bare tech jar gives you the library but not the autoconfiguration.
- The SQLite module is **`flyway-database-nc-sqlite`** (the "nc" = native-connectors line) — there is
  **no** `flyway-database-sqlite` artifact (it 404s), and Flyway refuses SQLite without it.

When editing migrations during development, remember SQLite **WAL** leaves `-wal`/`-shm` sidecar files:
delete `the.db`, `the.db-wal`, AND `the.db-shm` together when resetting, or you'll inspect a stale DB.

## SQLite reality checks (the gotchas)

- **One writer at a time.** SQLite serializes writes. Enable **WAL** mode for concurrent readers, and
  set a **busy_timeout** so a brief lock waits instead of throwing `SQLITE_BUSY`.
- **Connection pool.** With a file DB, keep the pool small. WAL makes concurrent reads fine; writes
  still serialize. A `maximum-pool-size` of 1–5 is plenty for M1.
- **In-memory is per-connection.** A plain `:memory:` DB is private to one connection and vanishes
  when it closes — useless across a pooled app. For tests prefer a **file** DB under `build/` (easy to
  truncate/reset and to inspect on failure), or `file::memory:?cache=shared` with pool size 1.
- **Dynamic typing.** SQLite stores what you give it. Be explicit in RowMappers (`getLong`, `getString`)
  and store timestamps as ISO-8601 TEXT or epoch millis consistently.

Apply pragmas via the JDBC URL so every connection gets them:

```
jdbc:sqlite:build/aiforum-test.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
```

## Profile-isolated datasource

Three profiles, three databases. `test` must never touch prod, and backups are disabled under `test`.

`application.yml` (shared; default profile dev):
```yaml
spring:
  profiles:
    default: dev
  flyway:
    enabled: true
    locations: classpath:db/migration
```

`application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:sqlite:build/aiforum-test.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
aiforum:
  backups:
    enabled: false        # ← rail-tested: backups OFF under test
```

`application-dev.yml` / `application-prod.yml` point at their own files (e.g.
`jdbc:sqlite:data/aiforum-dev.db`, `…/aiforum.db`) with backups enabled.

These URLs are *relative*, and xerial does NOT create the parent directory — so a fresh checkout
(where `data/` is gitignored, hence absent) used to fail at Flyway startup with
`[SQLITE_CANTOPEN] unable to open database file`. `DataDirectoryInitializer` (an
`org.springframework.boot.EnvironmentPostProcessor`, registered in `META-INF/spring.factories`) now
parses `spring.datasource.url`, strips the `jdbc:sqlite:` prefix + `?…` query, and creates the parent
dir before any bean — Flyway, Hikari — runs. So no manual `mkdir data` is needed; add a new datasource
profile and the dir is handled automatically.

### The profile guard (rail-tested config)

Config drifts silently, so assert it. A small bean validates the wiring at startup, and a rail
scenario ([[cucumber-spring-bdd]]) checks it from the outside:

```kotlin
@Component
class ProfileGuard(
    env: Environment,
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${aiforum.backups.enabled}") private val backups: Boolean,
) {
    init {
        if (env.activeProfiles.contains("test")) {
            require("test" in url) { "test profile must use the test DB, got: $url" }
            require(!backups) { "backups must be disabled under the test profile" }
        }
    }
}
```

## Schema + recursive CTEs (the heart of it)

The comment tree is a self-referencing table; branch context is a recursive CTE. Migration
`V1__schema.sql`:

```sql
CREATE TABLE comment (
    id         TEXT PRIMARY KEY,
    thread_id  TEXT NOT NULL,
    parent_id  TEXT REFERENCES comment(id),
    author_id  TEXT NOT NULL,
    body       TEXT NOT NULL,
    state      TEXT NOT NULL,            -- DRAFTING|POSTED|FAILED|CANCELLED
    depth      INTEGER NOT NULL,
    created_at TEXT NOT NULL             -- ISO-8601
);
CREATE INDEX idx_comment_parent ON comment(parent_id);
CREATE INDEX idx_comment_thread ON comment(thread_id);
```

**Ancestor path** (root → node) — the branch-only scope:

```sql
WITH RECURSIVE ancestors(id, parent_id, body, depth) AS (
    SELECT id, parent_id, body, depth FROM comment WHERE id = :nodeId
    UNION ALL
    SELECT c.id, c.parent_id, c.body, c.depth
    FROM comment c JOIN ancestors a ON c.id = a.parent_id
)
SELECT * FROM ancestors ORDER BY depth;     -- root first
```

**Subtree** (node → all descendants) — for depth-budget growth and full-thread assembly:

```sql
WITH RECURSIVE subtree(id, parent_id, body, depth) AS (
    SELECT id, parent_id, body, depth FROM comment WHERE id = :rootId
    UNION ALL
    SELECT c.id, c.parent_id, c.body, c.depth
    FROM comment c JOIN subtree s ON c.parent_id = s.id
)
SELECT * FROM subtree ORDER BY depth;
```

**Siblings** (under the current parent) — for the include-siblings toggle:

```sql
SELECT * FROM comment WHERE parent_id = :parentId AND id <> :nodeId ORDER BY created_at;
```

## Repository with NamedParameterJdbcTemplate

```kotlin
@Repository
class JdbcCommentRepository(private val jdbc: NamedParameterJdbcTemplate) : CommentRepository {

    private val mapper = RowMapper { rs, _ ->
        Comment(
            id = rs.getString("id"),
            parentId = rs.getString("parent_id"),
            body = rs.getString("body"),
            depth = rs.getInt("depth"),
        )
    }

    override fun ancestorPath(nodeId: String): List<Comment> =
        jdbc.query(ANCESTOR_SQL, mapOf("nodeId" to nodeId), mapper)

    override fun subtree(rootId: String): List<Comment> =
        jdbc.query(SUBTREE_SQL, mapOf("rootId" to rootId), mapper)
}
```

Inject the repository by constructor everywhere (the seam discipline from [[bdd-tiered-testing]]).

## Per-scenario reset

Cheapest reliable approach: `DELETE FROM` every table (children before parents, or
`PRAGMA foreign_keys=off` around it) then re-apply `db/fixtures/test-fixtures.sql`. `Flyway.clean()` +
`migrate()` per scenario also works but is slower; truncate+seed is preferred. The hook lives in
[[cucumber-spring-bdd]]'s `DatabaseResetHooks`.

## Verify

- `./gradlew tier1` runs `JdbcCommentRepositoryTest` against the real test DB, asserting the
  ancestor/subtree CTEs return the right nodes in depth order.
- App boots under `test` and Flyway applies migrations to `build/aiforum-test.db` (delete the file to
  start clean).
- The `ProfileGuard` throws if the test profile is ever pointed at a non-test URL or has backups on.
