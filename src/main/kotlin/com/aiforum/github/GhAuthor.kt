package com.aiforum.github

/**
 * The author-id scheme for an ingested GitHub commenter (Slice 2 of plan_docs/github-pr-threads.md). A PR
 * comment is stored on a forum node under a namespaced `gh:<login>` author id — distinct from a persona id
 * (a bare name) and from `owner`/`system`, so the avatar hue, the firewall's data-author hook, and any future
 * per-login logic stay stable. Identity only; the *display* of a gh author (@login, login initials) lives in
 * `web.AuthorLabel`.
 */
object GhAuthor {
    const val PREFIX = "gh:"

    /** The stored author id for a GitHub login. */
    fun id(login: String): String = PREFIX + login

    /** True if an author id is an ingested GitHub login. */
    fun isGitHub(authorId: String): Boolean = authorId.startsWith(PREFIX)

    /** The login behind a gh author id (the id unchanged if it isn't a GitHub author). */
    fun login(authorId: String): String = authorId.removePrefix(PREFIX)
}
