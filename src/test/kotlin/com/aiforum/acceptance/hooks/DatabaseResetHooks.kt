package com.aiforum.acceptance.hooks

import com.aiforum.acceptance.config.FailingRepositoryToggle
import com.aiforum.acceptance.config.ScriptableLlmClient
import io.cucumber.java.Before
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Resets the real test SQLite DB and the Tier-1 fakes before every scenario, so each scenario is
 * isolated (see the cucumber-spring-bdd skill). DB first (order 0), then fakes (order 10).
 *
 * NOT @Component: glue classes (steps/hooks) are instantiated by Cucumber, which injects their
 * constructor dependencies from the Spring context. Marking glue @Component causes Spring to also
 * auto-detect it → duplicate beans → cucumber-spring refuses to start.
 */
class DatabaseResetHooks(
    private val jdbc: JdbcTemplate,
    private val llm: ScriptableLlmClient,
    private val failingRepo: FailingRepositoryToggle,
) {
    @Before(order = 0)
    fun resetDatabase() {
        // children before parents (foreign_keys=on)
        listOf("vote", "event_log", "comment", "thread", "persona").forEach {
            jdbc.update("DELETE FROM $it")
        }
    }

    @Before(order = 10)
    fun resetFakes() {
        llm.reset()
        failingRepo.clear()
    }
}
