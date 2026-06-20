package com.aiforum.acceptance.config

import com.aiforum.domain.Comment
import com.aiforum.repo.CommentRepository
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Test-profile wrapper that turns the dead `FailingRepositoryToggle` into a real persistence fault at
 * the IO boundary (UX state E) WITHOUT mocking internal code: the real service/controller path runs,
 * and only the actual write throws — so the draft-preservation behaviour is genuinely exercised.
 *
 * `@Primary` so the service is wired with this one under `test`; `kotlin("plugin.spring")` opens the
 * `@Repository` superclass and its write methods, so no manual `open` is needed. The toggle is
 * one-shot (it disarms after firing) which models a transient blip and lets the marker write through.
 */
@Component
@Primary
@Profile("test")
class FailingCommentRepository(
    jdbc: JdbcTemplate,
    clock: Clock,
    private val toggle: FailingRepositoryToggle,
) : CommentRepository(jdbc, clock) {

    override fun insert(c: Comment) {
        failIfArmed()
        super.insert(c)
    }

    override fun update(c: Comment) {
        failIfArmed()
        super.update(c)
    }

    private fun failIfArmed() {
        if (toggle.failNextWrite) {
            toggle.failNextWrite = false
            throw IllegalStateException("simulated persistence failure (test seam)")
        }
    }
}
