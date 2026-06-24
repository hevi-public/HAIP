package com.aiforum.acceptance.config

import com.aiforum.dto.ReasoningLeak
import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubOverview
import com.aiforum.github.GitHubResult
import com.aiforum.github.Issue
import com.aiforum.github.PullRequest
import com.aiforum.github.RepoSummary
import com.aiforum.images.DescribeRequest
import com.aiforum.images.ImageDescriber
import com.aiforum.images.VisionUnavailableException
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
        // `leak` mirrors what the real parsers (ReplySanitizer) would attach to a leaked completion, so a
        // scenario can drive the reasoning-leak badge through the real persist/render path. Null = clean.
        data class Respond(val text: String, val leak: ReasoningLeak? = null) : Behavior
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
            is Behavior.Respond -> LlmResponse(behavior.text, behavior.leak)
            is Behavior.Fail -> throw behavior.ex()
            Behavior.HangUntilCancelled -> {
                while (!cancellation.isCancelled) Thread.sleep(10)
                throw com.aiforum.llm.LlmException.Cancelled()
            }
        }
    }
}

/**
 * The scriptable vision seam ([ImageDescriber]) under test — the sibling of [ScriptableLlmClient]. Steps
 * program the caption it returns (or make it fail), and it spies on every request so a scenario can assert
 * the vision model was actually invoked. Reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableImageDescriber : ImageDescriber {

    val received = CopyOnWriteArrayList<DescribeRequest>()

    @Volatile
    var nextCaption: String = "an attached image"

    @Volatile
    var failNext: Boolean = false

    override fun describe(request: DescribeRequest): String {
        received += request
        if (failNext) throw VisionUnavailableException("scripted vision failure")
        return nextCaption
    }

    fun reset() {
        received.clear()
        nextCaption = "an attached image"
        failNext = false
    }
}

/**
 * The scriptable GitHub seam ([GitHubClient]) under test — the sibling of [ScriptableLlmClient]. The real
 * GhCliGitHubClient is present but inert under test (disabled), so this @Primary fake stands in and steps
 * program the snapshot the /github page renders: an [overview] (repo + open PRs + open issues) or an
 * unavailable off-state. Reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableGitHubClient : GitHubClient {

    @Volatile
    var unavailableReason: String = "GitHub integration is off."

    @Volatile
    var repo: RepoSummary? = null

    val pulls = CopyOnWriteArrayList<PullRequest>()
    val issues = CopyOnWriteArrayList<Issue>()

    /** A repo present => an Ok snapshot; otherwise the unavailable off-state with the programmed reason. */
    override fun overview(): GitHubResult {
        val r = repo
        return if (r != null) GitHubResult.Ok(GitHubOverview(r, pulls.toList(), issues.toList()))
        else GitHubResult.Unavailable(unavailableReason)
    }

    fun reset() {
        unavailableReason = "GitHub integration is off."
        repo = null
        pulls.clear()
        issues.clear()
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
