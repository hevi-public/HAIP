package com.aiforum.shortcut

import com.aiforum.markdown.MarkdownRenderer
import com.aiforum.observability.event
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(ShortcutConfig::class.java)

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
        if (props.enabled) {
            val inlineLinks = if (MarkdownRenderer.storyLinkBaseUrl != null) "on" else "off"
            log.atInfo().setMessage("Shortcut integration enabled — base={} default-query='{}' inline sc-N links {}")
                .addArgument(props.baseUrl).addArgument(props.defaultQuery).addArgument(inlineLinks)
                .event(EV_STARTUP_ENABLED)
                .addKeyValue("baseUrl", props.baseUrl).addKeyValue("defaultQuery", props.defaultQuery)
                .addKeyValue("inlineLinks", inlineLinks)
                .log()
        } else {
            log.atInfo()
                .setMessage("Shortcut integration disabled — surfaces hidden (set aiforum.shortcut.enabled=true to enable)")
                .event(EV_STARTUP_DISABLED).log()
        }
    }

    private companion object {
        const val EV_STARTUP_ENABLED = "shortcut.startup.enabled"
        const val EV_STARTUP_DISABLED = "shortcut.startup.disabled"
    }
}
