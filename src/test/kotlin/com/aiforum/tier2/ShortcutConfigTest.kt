package com.aiforum.tier2

import com.aiforum.markdown.MarkdownRenderer
import com.aiforum.shortcut.ShortcutConfig
import com.aiforum.shortcut.ShortcutProperties
import com.aiforum.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2: [ShortcutConfig]'s startup wiring. It both flips on inline `sc-N` linkification and logs a
 * one-line enabled/disabled banner so an operator (and log tooling) can confirm from the boot log whether
 * the integration came up. Both are pinned here — the banner via its structured `event` id (logs are IO).
 * The config mutates the process-global [MarkdownRenderer.storyLinkBaseUrl], so we snapshot and restore
 * it around each test to keep the suite isolated.
 */
@Tag("tier2")
class ShortcutConfigTest {

    private var savedBaseUrl: String? = null

    @BeforeEach fun snapshot() { savedBaseUrl = MarkdownRenderer.storyLinkBaseUrl }

    @AfterEach fun restore() { MarkdownRenderer.storyLinkBaseUrl = savedBaseUrl }

    @Test
    fun `enabled — logs the startup-enabled event and switches inline links on`() {
        val config = ShortcutConfig(ShortcutProperties(enabled = true, workspaceSlug = "acme"))

        LogCapture.on(ShortcutConfig::class.java).use { logs ->
            config.wireInlineStoryLinks()
            val event = logs.withEvent("shortcut.startup.enabled").single()
            assertTrue(event.formattedMessage.contains("Shortcut integration enabled"))
            assertEquals("on", logs.keyValue(event, "inlineLinks"), "the slug means inline links are wired on")
        }
        assertNotNull(MarkdownRenderer.storyLinkBaseUrl, "enabling wires up the inline-link base URL")
    }

    @Test
    fun `disabled — logs the startup-disabled event and leaves inline links off`() {
        val config = ShortcutConfig(ShortcutProperties(enabled = false))

        LogCapture.on(ShortcutConfig::class.java).use { logs ->
            config.wireInlineStoryLinks()
            assertTrue(logs.withEvent("shortcut.startup.disabled").isNotEmpty())
        }
        assertNull(MarkdownRenderer.storyLinkBaseUrl, "a disabled integration leaves inline links off")
    }
}
