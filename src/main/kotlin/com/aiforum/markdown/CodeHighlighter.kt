package com.aiforum.markdown

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Server-side syntax highlighting by running highlight.js' core `highlight()` inside a single GraalJS
 * context. That core API is string-in / HTML-string-out with no DOM dependency, so no browser shim is
 * needed — we never touch `highlightElement`/`highlightAll`, which are the parts that need a DOM.
 *
 * highlight.js itself is vendored as a webjar (see build.gradle.kts) and read off the classpath, so the
 * build stays hermetic — there is no CDN fetch and no JS source checked into this repo.
 *
 * Threading: a GraalJS [Context] is NOT thread-safe, so every call is serialised on [lock]. For a
 * single-user / small-team tool the contention is negligible, and [MarkdownRenderer] caches rendered HTML
 * so each block is highlighted once for its lifetime. If concurrency ever makes the lock a bottleneck,
 * swap this one object for a small Context pool — callers only see [highlight] and don't care. The
 * trade-off ladder and the "when to grow past this" guidance live in plan_docs/markdown-rendering.md.
 *
 * The bundle is eval'd lazily on first use (it is ~127KB and the eval is heavy), not at class-load.
 */
object CodeHighlighter {
    private val lock = Any()

    /**
     * A JS function `(code, lang) -> String?` closed over the loaded `hljs`. Building it eval's the whole
     * bundle, so the [lazy] makes that a one-time cost paid on the first highlight. Holding this [Value]
     * keeps its owning Context alive for the app's lifetime (the Context is never closed — it is a
     * process-wide singleton).
     */
    private val highlightFn: Value by lazy { buildHighlightFn() }

    private fun buildHighlightFn(): Value {
        val context = Context.newBuilder("js")
            // We knowingly run interpreted on a stock (non-GraalVM) JDK — silence the startup warning.
            .option("engine.WarnInterpreterOnly", "false")
            .build()
        context.eval(Source.newBuilder("js", loadBundle(), "highlight.min.js").build())
        // Do the language guard in JS so an unknown language returns null (→ caller's plain-block
        // fallback) rather than throwing. ignoreIllegals keeps a malformed snippet from blowing up.
        return context.eval(
            "js",
            """
            (function (code, lang) {
              if (!hljs.getLanguage(lang)) return null;
              return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
            })
            """.trimIndent(),
        )
    }

    private fun loadBundle(): String {
        // Version-agnostic classpath lookup (the webjar version lives only in build.gradle.kts).
        val resources = PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/resources/webjars/highlightjs/*/highlight.min.js")
        val bundle = resources.firstOrNull()
            ?: error("highlight.min.js not found on the classpath — is the highlightjs webjar present?")
        return bundle.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Highlight [code] as [language], returning highlight.js' inner HTML — `<span class="hljs-…">` tokens
     * with the code already HTML-escaped by hljs. Returns `null` when the language is unknown/unsupported
     * or anything throws, so the caller can fall back to a plain escaped block. Never throws.
     */
    fun highlight(code: String, language: String): String? = synchronized(lock) {
        runCatching {
            val result = highlightFn.execute(code, language)
            if (result.isNull) null else result.asString()
        }.getOrNull()
    }
}
