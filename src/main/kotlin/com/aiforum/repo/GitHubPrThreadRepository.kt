package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock

/**
 * Maps an ingested GitHub pull request to the forum thread created for it (V18 `github_pr_thread`), so
 * "Discuss this PR" is idempotent — see plan_docs/github-pr-threads.md. `repo` is the "OWNER/REPO" the PR
 * belongs to (blank when `gh` infers it). `headSha` is captured for a future re-sync but read by nothing yet.
 */
@Repository
class GitHubPrThreadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    data class Mapping(
        val id: String,
        val repo: String,
        val prNumber: Int,
        val threadId: String,
        val headSha: String?,
    )

    fun insert(id: String, repo: String, prNumber: Int, threadId: String, headSha: String?) {
        jdbc.update(
            "INSERT INTO github_pr_thread(id, repo, pr_number, thread_id, head_sha, created_at) VALUES (?,?,?,?,?,?)",
            id, repo, prNumber, threadId, headSha, clock.instant().toString(),
        )
    }

    fun findByPr(repo: String, prNumber: Int): Mapping? =
        jdbc.query(
            "SELECT id, repo, pr_number, thread_id, head_sha FROM github_pr_thread WHERE repo = ? AND pr_number = ?",
            ::mapRow, repo, prNumber,
        ).firstOrNull()

    /**
     * Thread ids for the given PR numbers in one repo, keyed by number — so the /github page can badge each
     * PR row "View thread" vs "Discuss" in a single query. An empty input short-circuits (no SQL with an
     * empty `IN ()` list).
     */
    fun threadIdsByNumbers(repo: String, numbers: List<Int>): Map<Int, String> {
        if (numbers.isEmpty()) return emptyMap()
        val placeholders = numbers.joinToString(",") { "?" }
        val args = (listOf<Any>(repo) + numbers).toTypedArray()
        return jdbc.query(
            "SELECT pr_number, thread_id FROM github_pr_thread WHERE repo = ? AND pr_number IN ($placeholders)",
            { rs, _ -> rs.getInt("pr_number") to rs.getString("thread_id") },
            *args,
        ).toMap()
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        Mapping(
            rs.getString("id"),
            rs.getString("repo"),
            rs.getInt("pr_number"),
            rs.getString("thread_id"),
            rs.getString("head_sha"),
        )
}
