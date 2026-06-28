package com.aiforum.service

import com.aiforum.dto.ScopeMode
import com.aiforum.github.GitHubClient
import com.aiforum.github.PrThreadFormat
import com.aiforum.github.PullResult
import com.aiforum.repo.GitHubPrThreadRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Turns a GitHub pull request into a forum thread (plan_docs/github-pr-threads.md): fetch the PR through the
 * read-only [GitHubClient] seam, render it as the opening post ([PrThreadFormat]), then fire the same
 * create-time "Whole Topic + Anyone" summon a normal new thread does — so the AI dispatcher reads the OP and
 * routes the right persona(s) to summarise the change and discuss it.
 *
 * Idempotent: a PR already mapped to a thread ([GitHubPrThreadRepository]) returns that thread WITHOUT
 * re-fetching from `gh` or re-creating — so the /github "Discuss" button is safe to click twice.
 */
@Service
class GitHubPrIngestionService(
    private val github: GitHubClient,
    private val threads: ThreadRepository,
    private val map: GitHubPrThreadRepository,
    private val generation: GenerationService,
    // The repo the mapping is keyed by — the configured pin, or "" when `gh` infers it (fine for one repo).
    @Value("\${aiforum.github.repo:}") private val repo: String = "",
) {

    sealed interface Result {
        /** A new thread was created for the PR. */
        data class Created(val threadId: String) : Result
        /** The PR was already ingested; this is its existing thread (no re-fetch, no duplicate). */
        data class Existing(val threadId: String) : Result
        /** The PR couldn't be fetched (integration off, `gh` missing/unauthenticated, PR not found). */
        data class Unavailable(val reason: String) : Result
    }

    /**
     * Which of the given PR numbers already have a thread, keyed by number → thread id — so the /github
     * page can badge each row "View thread" vs "Discuss". Keeps the repo key in one place (here, not the
     * controller).
     */
    fun existingThreads(numbers: List<Int>): Map<Int, String> = map.threadIdsByNumbers(repo, numbers)

    fun ingest(number: Int): Result {
        map.findByPr(repo, number)?.let { return Result.Existing(it.threadId) }

        return when (val r = github.pull(number)) {
            is PullResult.Unavailable -> Result.Unavailable(r.reason)
            is PullResult.Ok -> {
                val pull = r.pull
                val threadId = UUID.randomUUID().toString()
                threads.insert(threadId, PrThreadFormat.title(pull), PrThreadFormat.body(pull))
                map.insert(UUID.randomUUID().toString(), repo, number, threadId, pull.headSha)
                // The opening post carries the PR; summon the room exactly as ThreadController.newThread does
                // (async "Whole Topic + Anyone" — the dispatcher reads the OP and routes the summary).
                generation.summonAsync(
                    threadId = threadId,
                    parentId = null,
                    personaIds = listOf(GenerationService.AUTO_PERSONA),
                    text = "",
                    scope = ScopeMode.WHOLE_THREAD,
                    routingScope = ScopeMode.WHOLE_THREAD,
                )
                Result.Created(threadId)
            }
        }
    }
}
