package com.aiforum.shortcut

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `aiforum.shortcut` — read-only integration with the Shortcut ticketing system (https://shortcut.com).
 *
 * Off by default: with [enabled] false (or no [apiToken]) the forum behaves exactly as before and the
 * Shortcut surfaces (rail box, /shortcut page, inline `sc-N` links) stay dark. Flip it on in a real
 * deployment by setting `aiforum.shortcut.enabled: true` and supplying a token — e.g.
 * `api-token: ${SHORTCUT_API_TOKEN:}` — created at https://app.shortcut.com/settings/account/api-tokens.
 *
 * The token is sent in the `Shortcut-Token` header (see [HttpShortcutClient]); only GET endpoints are
 * called, so this can never mutate Shortcut state.
 */
@ConfigurationProperties(prefix = "aiforum.shortcut")
data class ShortcutProperties(
    /** Master switch. When false the [HttpShortcutClient] bean isn't created and every surface is hidden. */
    val enabled: Boolean = false,
    /** Personal API token, sent as the `Shortcut-Token` header. */
    val apiToken: String = "",
    /** REST API base. Overridable mostly for tests; the real value is the public API host. */
    val baseUrl: String = "https://api.app.shortcut.com/api/v3",
    /** Workspace url-slug, used to build story links for inline `sc-N` refs without an API call. */
    val workspaceSlug: String = "",
    /** The query the right-rail box and the page's "Search" source run by default. */
    val defaultQuery: String = "is:started",
    /** The query behind the page's "Recently updated" source. */
    val recentQuery: String = "updated:-2w..*",
    /** Mention name (without the leading @) behind the page's "Owner's stories" source. */
    val ownerMentionName: String = "",
    /** How many stories the right-rail box shows. */
    val boxLimit: Int = 5,
    /** How many stories the /shortcut page shows (Shortcut search caps a page at 25). */
    val pageLimit: Int = 25,
)
