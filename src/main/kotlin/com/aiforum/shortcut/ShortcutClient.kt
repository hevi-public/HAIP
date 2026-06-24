package com.aiforum.shortcut

/**
 * The read-only Shortcut seam. Two GETs are all the forum needs:
 *  - [searchStories] — the stories for a feed (rail box / page), states left unresolved;
 *  - [workflowStates] — the id → name map [ShortcutService] uses to fill those states in.
 *
 * Splitting the two keeps each a single endpoint (simple to fake and to Tier-1 test), and lets the
 * service cache the rarely-changing workflow map across requests. Implementations may throw on transport
 * or API errors; [ShortcutService] is the layer that catches and degrades to [ShortcutStatus.ERROR].
 */
interface ShortcutClient {
    /** `GET /search/stories` — up to [pageSize] (≤25) cards for [query], with [StoryCard.state] blank. */
    fun searchStories(query: String, pageSize: Int): List<StoryCard>

    /** `GET /workflows` flattened to a workflow-state-id → state-name map. */
    fun workflowStates(): Map<Long, String>

    /**
     * Whether this client is currently usable. A real client is always active; the only override is the
     * acceptance test fake, which toggles this per scenario so the Shortcut surfaces stay dark in the
     * scenarios that don't opt into them (an off client reads as [ShortcutStatus.DISABLED], not an error).
     */
    fun isActive(): Boolean = true
}
