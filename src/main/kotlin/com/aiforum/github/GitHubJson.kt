package com.aiforum.github

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder

/**
 * Pure Tier-0 parsing of `gh ... --json` output into the [GitHubClient] view types. Holds NO IO — it is
 * fed the captured stdout string, so every field-extraction branch is unit-testable against canned `gh`
 * envelopes (the same split as [com.aiforum.llm.LlmResponseParser] vs ProcessLlmClient).
 *
 * Lenient on unknown fields: `gh --json` envelopes carry exactly the fields we ask for, but staying
 * lenient means a future field addition can't break parsing.
 */
object GitHubJson {

    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** `gh repo view --json nameWithOwner,description,url,defaultBranchRef,stargazerCount,issues,pullRequests`. */
    val REPO_FIELDS = "nameWithOwner,description,url,defaultBranchRef,stargazerCount,issues,pullRequests"

    /** `gh pr list --json number,title,author,url,isDraft,createdAt`. */
    val PR_FIELDS = "number,title,author,url,isDraft,createdAt"

    /** `gh issue list --json number,title,author,url,createdAt`. */
    val ISSUE_FIELDS = "number,title,author,url,createdAt"

    /** `gh pr view <n> --json …` — the in-depth single-PR fields (diff is fetched separately, `gh pr diff`). */
    val PULL_FIELDS = "number,title,author,url,state,isDraft,body,baseRefName,headRefName,headRefOid,files"

    fun parseRepo(json: String): RepoSummary {
        val env = mapper.readValue(json, RepoEnvelope::class.java)
        return RepoSummary(
            nameWithOwner = env.nameWithOwner,
            description = env.description?.takeIf { it.isNotBlank() },
            url = env.url,
            defaultBranch = env.defaultBranchRef?.name ?: "",
            stars = env.stargazerCount,
            openIssues = env.issues?.totalCount ?: 0,
            openPrs = env.pullRequests?.totalCount ?: 0,
        )
    }

    fun parsePulls(json: String): List<PullRequest> =
        mapper.readValue(json, Array<PrEnvelope>::class.java).map {
            PullRequest(
                number = it.number,
                title = it.title,
                author = it.author?.login ?: "ghost",
                url = it.url,
                isDraft = it.isDraft,
                createdAt = it.createdAt,
            )
        }

    fun parseIssues(json: String): List<Issue> =
        mapper.readValue(json, Array<IssueEnvelope>::class.java).map {
            Issue(
                number = it.number,
                title = it.title,
                author = it.author?.login ?: "ghost",
                url = it.url,
                createdAt = it.createdAt,
            )
        }

    /** Parse one `gh pr view --json` envelope. `diff` is left blank — the client fills it from `gh pr diff`. */
    fun parsePull(json: String): PullDetail {
        val env = mapper.readValue(json, PullEnvelope::class.java)
        return PullDetail(
            number = env.number,
            title = env.title,
            author = env.author?.login ?: "ghost",
            url = env.url,
            state = env.state,
            isDraft = env.isDraft,
            body = env.body,
            baseRef = env.baseRefName,
            headRef = env.headRefName,
            headSha = env.headRefOid,
            changedFiles = env.files.map { ChangedFile(it.path, it.additions, it.deletions) },
            diff = "",
        )
    }

    // --- wire envelopes (Kotlin module applies the defaults for any omitted field) ---

    private data class RepoEnvelope(
        val nameWithOwner: String = "",
        val description: String? = null,
        val url: String = "",
        val defaultBranchRef: Ref? = null,
        val stargazerCount: Int = 0,
        val issues: Count? = null,
        val pullRequests: Count? = null,
    )

    private data class Ref(val name: String = "")
    private data class Count(val totalCount: Int = 0)
    private data class Author(val login: String = "")

    private data class PrEnvelope(
        val number: Int = 0,
        val title: String = "",
        val author: Author? = null,
        val url: String = "",
        @param:JsonProperty("isDraft") val isDraft: Boolean = false,
        val createdAt: String = "",
    )

    private data class IssueEnvelope(
        val number: Int = 0,
        val title: String = "",
        val author: Author? = null,
        val url: String = "",
        val createdAt: String = "",
    )

    private data class FileEnvelope(
        val path: String = "",
        val additions: Int = 0,
        val deletions: Int = 0,
    )

    private data class PullEnvelope(
        val number: Int = 0,
        val title: String = "",
        val author: Author? = null,
        val url: String = "",
        val state: String = "",
        @param:JsonProperty("isDraft") val isDraft: Boolean = false,
        val body: String = "",
        val baseRefName: String = "",
        val headRefName: String = "",
        val headRefOid: String = "",
        val files: List<FileEnvelope> = emptyList(),
    )
}
