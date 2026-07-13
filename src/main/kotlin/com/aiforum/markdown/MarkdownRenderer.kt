package com.aiforum.markdown

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a reply/post body (GitHub-flavoured markdown) to trusted HTML for `$unsafe{}` output.
 *
 * SECURITY — bodies are LLM-generated, hence untrusted. The firewall behind `$unsafe{}` has two halves:
 * (1) [HtmlRenderer.Builder.escapeHtml] is on, so any raw HTML a body contains is rendered inert (shown
 * as text) — the only HTML in the output is what commonmark and our own renderers emit, and tables
 * therefore arrive as GFM markdown pipes, never raw `<table>`. (2) [HtmlRenderer.Builder.sanitizeUrls]
 * is on, so link/image *destinations* — which escapeHtml does NOT touch — are checked too: a
 * `javascript:`/`data:`/`vbscript:` href or src is emptied (the link text still renders, the URL is
 * gone) and links gain `rel="nofollow"`. Only http/https/mailto and protocol-less (relative) URLs
 * survive; `data:` is in commonmark's default allowlist but excluded here, since a `data:text/html`
 * link is script execution the same as `javascript:`.
 *
 * Code blocks: [HighlightedCodeBlockRenderer] hands each fenced block's text + declared language to
 * [CodeHighlighter]. No language, an unknown language, or any highlight failure falls back to a plain
 * escaped `<pre><code>` — highlighting is best-effort, never load-bearing for correctness.
 *
 * Results are cached by body content (bodies are immutable once posted), so each distinct body is
 * parsed and highlighted exactly once for its lifetime.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
        // Render a single newline as <br> rather than collapsing it to a space: forum replies are chatty
        // and authors expect their line breaks kept (this matches the prior plain-text/pre-wrap feel and
        // how GitHub comments behave). Paragraph breaks (blank line) still become separate <p> blocks.
        .softbreak("<br>\n")
        .nodeRendererFactory { context -> HighlightedCodeBlockRenderer(context) }
        .build()
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * When non-null, bare Shortcut refs (`sc-123`) in a body are linked to `storyLinkBaseUrl` + id (a
     * `…/story/` prefix) — see [StoryRefLinker]. Left null by default, so without the Shortcut
     * integration bodies render exactly as before. Set once at startup by
     * [com.aiforum.shortcut.ShortcutConfig]; `@Volatile` for safe publication to request threads.
     */
    @Volatile
    var storyLinkBaseUrl: String? = null

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val base = storyLinkBaseUrl
        // Key by the base too: the same body renders differently with linkification on vs off, so a stale
        // entry would otherwise leak across a startup-time toggle.
        val key = if (base == null) markdown else "$base $markdown"
        return cache.getOrPut(key) {
            val document = parser.parse(markdown)
            if (base != null) StoryRefLinker.apply(document, base)
            renderer.render(document)
        }
    }
}

/**
 * Overrides commonmark's default fenced-code rendering to run the block through [CodeHighlighter]. A
 * custom [NodeRenderer] declaring [FencedCodeBlock] takes precedence over the core renderer for that node.
 */
internal class HighlightedCodeBlockRenderer(
    private val context: HtmlNodeRendererContext,
) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(FencedCodeBlock::class.java)

    override fun render(node: Node) {
        node as FencedCodeBlock
        // Info string is e.g. "yaml" or "ts title=foo" — the language is the first whitespace-delimited word.
        val language = node.info?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase()?.ifEmpty { null }
        val code = node.literal
        val highlighted = language?.let { CodeHighlighter.highlight(code, it) }

        val writer = context.writer
        writer.line()
        writer.tag("pre")
        if (highlighted != null) {
            writer.tag("code", mapOf("class" to "hljs language-$language"))
            writer.raw(highlighted) // hljs already HTML-escaped the code text
        } else {
            // No language / unknown language / highlight failure → plain escaped block.
            val attrs = if (language != null) mapOf("class" to "language-$language") else emptyMap()
            writer.tag("code", attrs)
            writer.text(code) // text() HTML-escapes
        }
        writer.tag("/code")
        writer.tag("/pre")
        writer.line()
    }
}
