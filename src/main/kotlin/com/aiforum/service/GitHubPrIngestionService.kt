package com.aiforum.service

import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ScopeMode
import com.aiforum.github.GhAuthor
import com.aiforum.github.GitHubClient
import com.aiforum.github.PrThreadFormat
import com.aiforum.github.PullDetail
import com.aiforum.github.PullResult
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.GitHubPrThreadRepository
import com.aiforum.repo.ThreadRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Turns a GitHub pull request into a forum thread (plan_docs/github-pr-threads.md): fetch the PR through the
 * read-only [GitHubClient] seam, render it as the opening post ([PrThreadFormat]), post the PR's review/issue
 * discussion as nodes (Slice 2), then fire the same create-time "Whole Topic + Anyone" summon a normal new
 * thread does — so the AI dispatcher reads the OP *and the discussion* and routes the right persona(s) to
 * summarise the change and weigh in.
 *
 * Idempotent: a PR already mapped to a thread ([GitHubPrThreadRepository]) returns that thread WITHOUT
 * re-fetching from `gh` or re-creating — so the /github "Discuss" button is safe to click twice.
 */
@Service
class GitHubPrIngestionService(
    private val github: GitHubClient,
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val map: GitHubPrThreadRepository,
    private val generation: GenerationService,
    private val clock: Clock,
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
                // Post the PR's discussion as top-level nodes BEFORE the summon, so the room reads what the
                // team already said when it summarises and weighs in.
                postDiscussion(threadId, pull)
                // The opening post + discussion are in place; summon the room exactly as
                // ThreadController.newThread does (async "Whole Topic + Anyone" — the dispatcher reads the
                // whole topic and routes the summary).
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

    /**
     * Insert each PR comment/review as a top-level POSTED node authored by `gh:<login>` (a non-persona
     * voice, rendered as @login). Stamped with the comment's real GitHub timestamp so the nodes order
     * chronologically; a blank/unparseable timestamp falls back to "now". Top-level (parentId null) under
     * the post, like a reply to the OP — inline-comment nesting is deferred (see the plan doc).
     */
    private fun postDiscussion(threadId: String, pull: PullDetail) {
        pull.comments.forEach { c ->
            val createdAt = runCatching { Instant.parse(c.createdAt) }.getOrDefault(clock.instant())
            comments.insertAt(
                Comment(
                    id = UUID.randomUUID().toString(),
                    threadId = threadId,
                    parentId = null,
                    authorId = GhAuthor.id(c.author),
                    body = PrThreadFormat.commentBody(c),
                    state = GenerationState.POSTED,
                    failureCategory = null,
                    depth = 0,
                ),
                createdAt,
            )
        }
    }
}
