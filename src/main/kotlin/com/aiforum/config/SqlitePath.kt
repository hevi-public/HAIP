package com.aiforum.config

/**
 * Pure parsing/expansion of a `jdbc:sqlite:` datasource URL — no filesystem, no Spring, no environment
 * access, so it's unit-tested at tier 0. [DataDirectoryInitializer] is the thin IO glue on top.
 *
 * The whole point is the **`~` guarantee**: a leading shell tilde is something the JVM does NOT expand,
 * so `Path.of("~/x")` would silently create a junk `~` directory in the cwd (and xerial would open the
 * wrong file). Here a leading `~` / `~/…` is resolved against [homeDir] up front, so a literal `~` can
 * never reach disk. We deliberately leave `/` and `\` alone (on Unix `/` is the separator and `\` is a
 * legitimate filename character) and don't touch env-var shell-isms like `$HOME` (the correct idiom
 * `${user.home}` is already resolved by Spring before the URL reaches us).
 */
object SqlitePath {

    private const val PREFIX = "jdbc:sqlite:"

    /**
     * The `~`-expanded result for a *file-backed* SQLite URL, or `null` for a non-sqlite URL or an
     * in-memory database (which has no file to create). [filePath] and [url] never contain a leading `~`.
     */
    data class Resolved(val filePath: String, val url: String)

    fun expand(url: String, homeDir: String): Resolved? {
        if (!url.startsWith(PREFIX)) return null
        val body = url.removePrefix(PREFIX)
        // Strip any `?journal_mode=…` query string to get the bare file path.
        val path = body.substringBefore('?')
        // In-memory databases have no backing file, so there's nothing to create.
        if (path.isBlank() || path == ":memory:" || path.startsWith("file::memory:")) return null

        val expanded = expandTilde(path, homeDir)
        if (expanded == path) return Resolved(path, url)

        val query = body.substringAfter('?', "")
        val expandedUrl = if (query.isEmpty()) "$PREFIX$expanded" else "$PREFIX$expanded?$query"
        return Resolved(expanded, expandedUrl)
    }

    private fun expandTilde(path: String, homeDir: String): String = when {
        path == "~" -> homeDir.trimEnd('/')
        path.startsWith("~/") -> homeDir.trimEnd('/') + path.substring(1)
        else -> path   // not a leading-tilde path (incl. `~user`, separators) — leave untouched
    }
}
