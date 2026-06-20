package com.aiforum.acceptance.config

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The scriptable Tier-1 IO double (see the cucumber-spring-bdd skill). Steps program it per scenario
 * to return canned output or throw a specific failure, and it SPIES on every request it received so
 * the +1 firewall and context-scoping scenarios can assert on what the model was actually handed.
 *
 * It's a singleton bean reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableLlmClient : LlmClient {

    sealed interface Behavior {
        data class Respond(val text: String) : Behavior
        data class Fail(val ex: () -> RuntimeException) : Behavior
        /** Block until the cancellation token is tripped, then report cancellation. */
        object HangUntilCancelled : Behavior
    }

    private val script = ConcurrentLinkedDeque<Behavior>()

    /** The spy: every request handed to the client, in order. CopyOnWriteArrayList because the async
     *  summon path writes from a worker thread while steps read it from the test thread — COW gives
     *  safe iteration and visibility without locking the readers. */
    val received = CopyOnWriteArrayList<LlmRequest>()

    fun enqueue(behavior: Behavior) = script.addLast(behavior)

    fun reset() {
        script.clear()
        received.clear()
    }

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        received += request
        return when (val behavior = script.pollFirst() ?: Behavior.Respond("default reply")) {
            is Behavior.Respond -> LlmResponse(behavior.text)
            is Behavior.Fail -> throw behavior.ex()
            Behavior.HangUntilCancelled -> {
                while (!cancellation.isCancelled) Thread.sleep(10)
                throw com.aiforum.llm.LlmException.Cancelled()
            }
        }
    }
}

/**
 * A boundary toggle for simulating persistence failure (category E) WITHOUT mocking internal code:
 * a repository wrapper reads this flag and throws on the next write, so the real service/controller
 * path still runs and the draft-preservation behaviour is genuinely exercised.
 */
@Component
@Profile("test")
class FailingRepositoryToggle {
    @Volatile
    var failNextWrite: Boolean = false

    fun clear() {
        failNextWrite = false
    }
}

/**
 * Fixed clock under test so timestamps and Retry-After windows are deterministic and assertable.
 */
@Configuration
@Profile("test")
class FixedClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
}
