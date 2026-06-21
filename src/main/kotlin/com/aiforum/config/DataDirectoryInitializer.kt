package com.aiforum.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import java.nio.file.Files

/**
 * Creates the parent directory of the SQLite database file before anything tries to open it.
 *
 * The dev/prod datasource URL is a *relative* `jdbc:sqlite:data/aiforum.db`, and xerial's driver does
 * NOT create the parent dir — so on a fresh checkout (where `data/` is gitignored and therefore absent)
 * the very first `bootRun` died at Flyway startup with `[SQLITE_CANTOPEN] unable to open database file`.
 *
 * An [EnvironmentPostProcessor] runs after the environment is prepared (so the profile-specific
 * datasource URL is fully resolved) but well before any bean — including Flyway and the Hikari
 * datasource — is created, which is exactly the window we need. Registered in
 * `META-INF/spring.factories`.
 */
class DataDirectoryInitializer : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val url = environment.getProperty("spring.datasource.url") ?: return
        val parent = sqliteParentDir(url) ?: return
        Files.createDirectories(parent)
    }

    private fun sqliteParentDir(url: String): java.nio.file.Path? {
        if (!url.startsWith(SQLITE_PREFIX)) return null
        // Strip the `jdbc:sqlite:` prefix and any `?journal_mode=…` query string.
        val path = url.removePrefix(SQLITE_PREFIX).substringBefore('?')
        // In-memory databases have no file to back, so there's nothing to create.
        if (path.isBlank() || path == ":memory:" || path.startsWith("file::memory:")) return null
        return java.nio.file.Path.of(path).toAbsolutePath().parent
    }

    private companion object {
        const val SQLITE_PREFIX = "jdbc:sqlite:"
    }
}
