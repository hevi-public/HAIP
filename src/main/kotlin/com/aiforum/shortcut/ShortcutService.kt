package com.aiforum.shortcut

import com.aiforum.observability.event
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * The forum-facing facade over [ShortcutClient]. Everything that displays Shortcut data goes through
 * here so the three surfaces (rail box, /shortcut page, inline links) read identically and degrade the
 * same way:
 *  - integration off / no client  → [ShortcutStatus.DISABLED] (surface hides itself);
 *  - call fails                    → [ShortcutStatus.ERROR] (surface shows a quiet note), never a 500.
 *
 * The client bean is optional (it only exists when `aiforum.shortcut.enabled: true`), so it's injected
 * via [ObjectProvider] and resolved defensively. The workflow-state map is fetched once and cached for
 * the process — states change rarely, and it spares every story read a second round-trip.
 */
@Service
class ShortcutService(
    private val props: ShortcutProperties,
    private val clientProvider: ObjectProvider<ShortcutClient>,
) {
    private val log = LoggerFactory.getLogger(ShortcutService::class.java)
    private val statesCache = AtomicReference<Map<Long, String>?>(null)

    /** Drop the cached workflow-state map (the acceptance suite calls this between scenarios). */
    fun evictCaches() = statesCache.set(null)

    /** True when a story read could actually run (integration on AND an active client present). */
    val enabled: Boolean
        get() = props.enabled && clientProvider.ifAvailable?.isActive() == true

    /** The right-rail box feed: the configured default query, capped at the box limit. */
    fun boxStories(): ShortcutResult = stories(props.defaultQuery, props.boxLimit)

    /** The /shortcut page feed for a chosen [source] (and optional free-text [q] for the Search source). */
    fun pageStories(source: StorySource, q: String?): ShortcutResult {
        val query = when (source) {
            StorySource.QUERY -> q?.takeIf { it.isNotBlank() } ?: props.defaultQuery
            StorySource.RECENT -> props.recentQuery
            StorySource.OWNER -> {
                if (props.ownerMentionName.isBlank()) {
                    return ShortcutResult.error(
                        "Set aiforum.shortcut.owner-mention-name to use the owner's-stories view.",
                    )
                }
                "owner:${props.ownerMentionName}"
            }
        }
        return stories(query, props.pageLimit)
    }

    /** Run one search, resolve state names, and wrap the outcome — catching any failure into ERROR. */
    fun stories(query: String, limit: Int): ShortcutResult {
        val client = clientProvider.ifAvailable
        if (!props.enabled || client == null || !client.isActive()) {
            log.atDebug().setMessage("Shortcut read skipped — integration disabled")
                .event(EV_READ_SKIPPED).log()
            return ShortcutResult.DISABLED
        }
        val effective = query.ifBlank { props.defaultQuery }
        return try {
            val cards = ShortcutMapper.resolveStates(client.searchStories(effective, limit), states(client))
            log.atDebug().setMessage("Shortcut read ok — query='{}' limit={} stories={}")
                .addArgument(effective).addArgument(limit).addArgument(cards.size)
                .event(EV_READ_OK)
                .addKeyValue("query", effective).addKeyValue("limit", limit).addKeyValue("stories", cards.size)
                .log()
            ShortcutResult.ok(cards, effective)
        } catch (e: Exception) {
            log.atWarn().setMessage("Shortcut read failed for query '{}': {}")
                .addArgument(effective).addArgument(e.toString())
                .event(EV_READ_FAILED).addKeyValue("query", effective).addKeyValue("error", e.toString())
                .log()
            ShortcutResult.error("Couldn't reach Shortcut — check the API token and network.", effective)
        }
    }

    /**
     * The canonical Shortcut URL for a story id, built from config alone (no API call) — used by inline
     * `sc-N` links. With a workspace slug configured it's the exact deep link; without one it falls back
     * to the slug-less `/story/{id}` form, which Shortcut redirects to the right workspace.
     */
    fun storyUrl(publicId: Long): String = storyLinkBase() + publicId

    /** The `…/story/` prefix inline links concatenate the id onto. */
    fun storyLinkBase(): String =
        if (props.workspaceSlug.isBlank()) {
            "https://app.shortcut.com/story/"
        } else {
            "https://app.shortcut.com/${props.workspaceSlug}/story/"
        }

    /** Cached workflow-state map; only a successful, non-empty fetch is cached (failures stay retryable). */
    private fun states(client: ShortcutClient): Map<Long, String> {
        statesCache.get()?.let { return it }
        val fetched = runCatching { client.workflowStates() }
            .onFailure {
                log.atWarn().setMessage("Shortcut workflow fetch failed: {}").addArgument(it.toString())
                    .event(EV_WORKFLOW_FAILED).addKeyValue("error", it.toString()).log()
            }
            .getOrDefault(emptyMap())
        if (fetched.isNotEmpty()) statesCache.set(fetched)
        return fetched
    }

    private companion object {
        // Structured event-ids — the stable, machine-readable identity log tooling keys off (see the
        // bdd-tiered-testing skill, "Logging is IO"). The prose message is free to change; these are not.
        const val EV_READ_OK = "shortcut.read.ok"
        const val EV_READ_SKIPPED = "shortcut.read.skipped"
        const val EV_READ_FAILED = "shortcut.read.failed"
        const val EV_WORKFLOW_FAILED = "shortcut.workflow.failed"
    }
}
