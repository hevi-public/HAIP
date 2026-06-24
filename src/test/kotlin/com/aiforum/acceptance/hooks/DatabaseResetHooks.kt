package com.aiforum.acceptance.hooks

import com.aiforum.acceptance.config.FailingRepositoryToggle
import com.aiforum.acceptance.config.ScriptableGitHubClient
import com.aiforum.acceptance.config.ScriptableImageDescriber
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.service.InFlightGenerations
import io.cucumber.java.Before
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Resets the real test SQLite DB and the Tier-1 fakes before every scenario, so each scenario is
 * isolated (see the cucumber-spring-bdd skill). In-flight workers first (so a lingering async draft
 * can't write into a freshly-cleared DB), then DB (order 0), then fakes (order 10).
 *
 * NOT @Component: glue classes (steps/hooks) are instantiated by Cucumber, which injects their
 * constructor dependencies from the Spring context. Marking glue @Component causes Spring to also
 * auto-detect it → duplicate beans → cucumber-spring refuses to start.
 */
class DatabaseResetHooks(
    private val jdbc: JdbcTemplate,
    private val llm: ScriptableLlmClient,
    private val describer: ScriptableImageDescriber,
    private val failingRepo: FailingRepositoryToggle,
    private val github: ScriptableGitHubClient,
    private val inFlight: InFlightGenerations,
) {
    @Before(order = -10)
    fun cancelInFlight() {
        // Seatbelt: trip + join any async draft left running by a prior scenario before the DB is wiped.
        inFlight.reset()
    }

    @Before(order = 0)
    fun resetDatabase() {
        // children before parents (foreign_keys=on) — attachment + comment_revision reference comment, so first.
        listOf("attachment", "vote", "comment_revision", "event_log", "comment", "thread_read", "thread", "persona").forEach {
            jdbc.update("DELETE FROM $it")
        }
    }

    @Before(order = 10)
    fun resetFakes() {
        llm.reset()
        describer.reset()
        github.reset()
        failingRepo.clear()
    }
}
