package com.aiforum.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates the parent directory of the SQLite database file before anything tries to open it, and makes
 * sure a leading `~` in the datasource URL never reaches the filesystem.
 *
 * The dev/prod datasource URL is a *relative* `jdbc:sqlite:data/aiforum.db`, and xerial's driver does
 * NOT create the parent dir — so on a fresh checkout (where `data/` is gitignored and therefore absent)
 * the very first `bootRun` died at Flyway startup with `[SQLITE_CANTOPEN] unable to open database file`.
 *
 * Prod points at the home directory; the canonical form uses Spring's `${user.home}` placeholder, which
 * is already resolved by the time we read the property. But as a backstop — so a literal `~/…` URL from
 * a future config / env override / CLI arg can never create a junk `~` directory in the cwd — we expand
 * a leading `~` ourselves ([SqlitePath]) and, if that changed anything, **republish** the resolved URL
 * so xerial/Hikari open the same absolute file (otherwise the dir would be made at the real home while
 * the driver wrote to a literal `~`).
 *
 * An [EnvironmentPostProcessor] runs after the environment is prepared (so the profile-specific
 * datasource URL is fully resolved) but well before any bean — including Flyway and the Hikari
 * datasource — is created, which is exactly the window we need. Registered in `META-INF/spring.factories`.
 */
class DataDirectoryInitializer : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val rawUrl = environment.getProperty("spring.datasource.url") ?: return
        val homeDir = System.getProperty("user.home") ?: return
        val resolved = SqlitePath.expand(rawUrl, homeDir) ?: return

        // The only IO: create the parent dir before Flyway/Hikari open the file.
        Path.of(resolved.filePath).toAbsolutePath().parent?.let { Files.createDirectories(it) }

        // If we expanded a `~`, the driver must use the same absolute path we just created the dir for.
        // addFirst => highest precedence, overriding the profile YAML; it lands before the datasource bean.
        if (resolved.url != rawUrl) {
            environment.propertySources.addFirst(
                MapPropertySource(
                    "aiforum-expanded-datasource-url",
                    mapOf("spring.datasource.url" to resolved.url),
                ),
            )
        }
    }
}
