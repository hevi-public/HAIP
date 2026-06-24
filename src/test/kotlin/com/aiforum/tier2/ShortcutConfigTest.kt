package com.aiforum.tier2

import ch.qos.logback.classic.Level
import com.aiforum.markdown.MarkdownRenderer
import com.aiforum.shortcut.ShortcutConfig
import com.aiforum.shortcut.ShortcutProperties
import com.aiforum.support.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2: [ShortcutConfig]'s startup wiring. It both flips on inline `sc-N` linkification and logs a
 * one-line enabled/disabled banner so an operator can confirm from the boot log whether the integration
 * came up. Both are pinned here. The config mutates the process-global [MarkdownRenderer.storyLinkBaseUrl],
 * so we snapshot and restore it around each test to keep the suite isolated.
 */
@Tag("tier2")
class ShortcutConfigTest {

    private var savedBaseUrl: String? = null

    @BeforeEach fun snapshot() { savedBaseUrl = MarkdownRenderer.storyLinkBaseUrl }

    @AfterEach fun restore() { MarkdownRenderer.storyLinkBaseUrl = savedBaseUrl }

    @Test
    fun `enabled — logs an enabled banner and switches inline links on`() {
        val config = ShortcutConfig(ShortcutProperties(enabled = true, workspaceSlug = "acme"))

        LogCapture.around(ShortcutConfig::class.java) { logs ->
            config.wireInlineStoryLinks()
            assertTrue(logs.has(Level.INFO, "Shortcut integration enabled"))
        }
        assertNotNull(MarkdownRenderer.storyLinkBaseUrl, "enabling wires up the inline-link base URL")
    }

    @Test
    fun `disabled — logs a disabled banner and leaves inline links off`() {
        val config = ShortcutConfig(ShortcutProperties(enabled = false))

        LogCapture.around(ShortcutConfig::class.java) { logs ->
            config.wireInlineStoryLinks()
            assertTrue(logs.has(Level.INFO, "Shortcut integration disabled"))
        }
        assertNull(MarkdownRenderer.storyLinkBaseUrl, "a disabled integration leaves inline links off")
    }
}
