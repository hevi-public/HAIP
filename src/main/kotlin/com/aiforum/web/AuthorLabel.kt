package com.aiforum.web

import com.aiforum.github.GhAuthor

/**
 * Maps a stored author id to its display form for the templates. Most authors render verbatim — a persona's
 * id IS its name, and "owner"/"system" show as-is. A GitHub author (an ingested PR comment, Slice 2 of
 * plan_docs/github-pr-threads.md) is stored as "gh:<login>" (see [GhAuthor]); here it renders as "@<login>"
 * with the login's own initials, so a team-mate reads as a team-mate rather than "gh:octocat" / a "GH"
 * monogram. The raw id is kept on the data-author attribute — only the visible name/monogram are mapped.
 */
object AuthorLabel {
    /** The visible author name: a GitHub author as @login, everyone else as their id. */
    fun display(authorId: String): String =
        if (GhAuthor.isGitHub(authorId)) "@" + GhAuthor.login(authorId) else authorId

    /** The 1–2 char monogram: a GitHub author uses its login's initials, not "GH". */
    fun monogram(authorId: String): String =
        (if (GhAuthor.isGitHub(authorId)) GhAuthor.login(authorId) else authorId).take(2).uppercase()
}
