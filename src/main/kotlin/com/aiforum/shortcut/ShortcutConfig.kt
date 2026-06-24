package com.aiforum.shortcut

import com.aiforum.markdown.MarkdownRenderer
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Enables [ShortcutProperties] and, when the integration is on, switches on inline `sc-N` linkification
 * in the shared [MarkdownRenderer]. The renderer is provider-agnostic (it just needs a `…/story/` URL
 * prefix), so the Shortcut concern is wired here at startup rather than baked into the renderer.
 *
 * Left null when disabled, so a forum without Shortcut renders bodies exactly as before.
 */
@Configuration
@EnableConfigurationProperties(ShortcutProperties::class)
class ShortcutConfig(private val props: ShortcutProperties) {

    @PostConstruct
    fun wireInlineStoryLinks() {
        MarkdownRenderer.storyLinkBaseUrl = if (props.enabled) {
            if (props.workspaceSlug.isBlank()) {
                "https://app.shortcut.com/story/"
            } else {
                "https://app.shortcut.com/${props.workspaceSlug}/story/"
            }
        } else {
            null
        }
    }
}
