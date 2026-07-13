package com.aiforum.tier1.repo

import com.aiforum.dto.ScopeMode
import com.aiforum.repo.RoutingEventRepository
import com.aiforum.service.RoutingOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Duration

/**
 * Tier-1: RoutingEventRepository against the real test SQLite DB (see the bdd-tiered-testing skill).
 * Pins the V15 `routing_event` round-trip — outcome counts feed the stats page, and the raw reply is
 * kept only for parse misses. The fixed test clock makes the "last 7 days" window deterministic.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class RoutingEventRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var events: RoutingEventRepository
    @Autowired lateinit var clock: Clock

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM routing_event")
    }

    private fun record(outcome: RoutingOutcome, picked: Int = 1, rawReply: String? = null) =
        events.record(outcome, rosterSize = 4, pickedCount = picked, routingScope = ScopeMode.WHOLE_THREAD, rawReply = rawReply)

    @Test
    fun `counts group recorded outcomes by bucket`() {
        record(RoutingOutcome.MATCHED, picked = 2)
        record(RoutingOutcome.MATCHED, picked = 1)
        record(RoutingOutcome.WIDENED_NO_MATCH, picked = 4, rawReply = "the backend folks")

        val counts = events.counts()

        assertEquals(2, counts[RoutingOutcome.MATCHED])
        assertEquals(1, counts[RoutingOutcome.WIDENED_NO_MATCH])
        assertEquals(null, counts[RoutingOutcome.FAILED_GENERATION], "an unseen outcome is simply absent")
    }

    @Test
    fun `recentMisses returns only widened events, newest first, with their raw reply`() {
        record(RoutingOutcome.MATCHED, picked = 1)
        record(RoutingOutcome.WIDENED_NO_MATCH, picked = 4, rawReply = "ask the Kotlin person")
        record(RoutingOutcome.WIDENED_NO_MATCH, picked = 4, rawReply = "the backend folks")

        val misses = events.recentMisses(10)

        assertEquals(2, misses.size, "only parse misses are listed")
        assertEquals("the backend folks", misses.first().reply, "newest miss first (id-descending tiebreak)")
        assertTrue(misses.all { it.scope == ScopeMode.WHOLE_THREAD.name })
    }

    @Test
    fun `the since window filters out events older than the cutoff`() {
        record(RoutingOutcome.MATCHED)
        record(RoutingOutcome.WIDENED_NO_MATCH, rawReply = "no one")
        val now = clock.instant()

        // A week-ago cutoff keeps everything just recorded; a future cutoff filters all of it out, proving
        // the WHERE clause genuinely bounds the window rather than ignoring `since`.
        assertEquals(2, events.counts(now.minus(Duration.ofDays(7))).values.sum())
        assertEquals(0, events.counts(now.plusSeconds(1)).values.sum())
    }
}
