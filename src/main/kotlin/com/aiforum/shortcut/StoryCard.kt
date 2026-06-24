package com.aiforum.shortcut

/** Status of a story fetch, so the UI can degrade gracefully instead of erroring. */
enum class ShortcutStatus {
    /** Integration off (or no client) — the surface renders nothing at all. */
    DISABLED,

    /** A call was attempted but failed (bad token, network, API error) — the surface shows a quiet note. */
    ERROR,

    /** A successful read — render the stories (possibly an empty list). */
    OK,
}

/**
 * The three story feeds the /shortcut page switches between. Each maps onto one Shortcut search query
 * (built by [ShortcutService]); the rail box always uses [QUERY] with the configured default query.
 */
enum class StorySource(val slug: String, val label: String) {
    QUERY("query", "Search"),
    RECENT("recent", "Recently updated"),
    OWNER("owner", "Owner's stories"),
    ;

    companion object {
        /** Parse the `?source=` slug; anything unknown falls back to [QUERY]. */
        fun from(slug: String?): StorySource = entries.firstOrNull { it.slug == slug } ?: QUERY
    }
}

/**
 * One Shortcut story as shown in the forum. [state] is the resolved workflow-state *name* (e.g. "In
 * Progress"); it may be blank when the workflow map couldn't be loaded. [url] is the story's canonical
 * `app_url` straight from the API.
 */
data class StoryCard(
    val publicId: Long,
    val name: String,
    val type: String,
    val stateId: Long?,
    val state: String,
    val url: String,
) {
    /** The human reference Shortcut uses, e.g. `sc-123`. */
    val ref: String get() = "sc-$publicId"

    /** A small glyph per story type for the card chrome. */
    val typeIcon: String
        get() = when (type) {
            "bug" -> "🐞"      // 🐞
            "chore" -> "🧹"    // 🧹
            "feature" -> "✦"        // ✦
            else -> "•"             // •
        }
}

/**
 * Outcome of a story read for one surface. The view model the controllers hand to JTE: it carries the
 * [status] so a template can branch (hide / quiet-error / render) without the controller throwing.
 */
data class ShortcutResult(
    val status: ShortcutStatus,
    val stories: List<StoryCard> = emptyList(),
    val query: String = "",
    val message: String? = null,
) {
    val isOk: Boolean get() = status == ShortcutStatus.OK

    companion object {
        val DISABLED = ShortcutResult(ShortcutStatus.DISABLED)
        fun ok(stories: List<StoryCard>, query: String) = ShortcutResult(ShortcutStatus.OK, stories, query)
        fun error(message: String, query: String = "") =
            ShortcutResult(ShortcutStatus.ERROR, query = query, message = message)
    }
}
