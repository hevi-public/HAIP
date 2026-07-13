package com.aiforum.github

/**
 * Pure Tier-0 formatting of a [PullDetail] into a forum thread's opening post — a title and a markdown body
 * (the PR description, a changed-file list, and a truncated diff). Holds NO IO, so every branch is
 * unit-testable.
 *
 * The body is rendered downstream through the escaping `MarkdownRenderer` (the diff in a fenced ```diff
 * block highlights as a diff), AND it is fed to the room as the opening-post context. The diff is therefore
 * line-capped at [DIFF_LINE_BUDGET] so a large PR can't blow the model's context window — the elided tail
 * links out to the PR on GitHub.
 */
object PrThreadFormat {

    /** Max diff lines embedded in the opening post; beyond this the diff is elided with a link to the PR. */
    const val DIFF_LINE_BUDGET = 300

    /** `#123 — Add gh MCP`. */
    fun title(pull: PullDetail): String = "#${pull.number} — ${pull.title}"

    /** The opening-post markdown: description, then a meta line, then changed files, then the diff. */
    fun body(pull: PullDetail): String {
        val sb = StringBuilder()

        // Lead with the PR description (the author's own words), when there is one.
        val description = pull.body.trim()
        if (description.isNotEmpty()) sb.append(description).append("\n\n")

        // A one-line provenance line: link to the PR, author, branch direction, state.
        val draft = if (pull.isDraft) " · draft" else ""
        sb.append("**[PR #${pull.number} on GitHub](${pull.url})** by ${pull.author} · ")
            .append("`${pull.baseRef} ← ${pull.headRef}`").append(" · ").append(pull.state.lowercase())
            .append(draft).append("\n\n")

        // Changed files with their add/del counts.
        if (pull.changedFiles.isNotEmpty()) {
            sb.append("## Changed files (${pull.changedFiles.size})\n\n")
            pull.changedFiles.forEach { sb.append("- `${it.path}` +${it.additions}/-${it.deletions}\n") }
            sb.append("\n")
        }

        // The diff, fenced (highlights as a diff) and capped to the line budget. Unified-diff lines are
        // always prefixed (space/+/-) or a header (diff/index/@@/---/+++), so a bare ``` can't appear to
        // close the fence early.
        val diff = pull.diff.trim()
        if (diff.isNotEmpty()) {
            val lines = diff.lines()
            sb.append("## Diff\n\n```diff\n").append(lines.take(DIFF_LINE_BUDGET).joinToString("\n")).append("\n```\n")
            if (lines.size > DIFF_LINE_BUDGET) {
                sb.append("\n> Diff truncated to $DIFF_LINE_BUDGET of ${lines.size} lines — ")
                    .append("[see the full diff on GitHub](${pull.url}/files).\n")
            }
        }

        return sb.toString().trimEnd()
    }
}
