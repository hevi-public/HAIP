# Markdown rendering — reply/post bodies, with server-side syntax highlighting

Status: **shipped** (2026-06). Replaces the previous plain-text bodies (HTML-escaped text rendered with
`white-space: pre-wrap`).

Goal: render reply and post bodies as GitHub-flavoured markdown — paragraphs, lists, tables, **and**
syntax-highlighted fenced code blocks — server-side, hermetically (no CDN), without opening an XSS hole.

## Pipeline

Bodies are stored as raw markdown and rendered to HTML at the view boundary; nothing is precomputed or
stored as HTML, so the change is reversible.

- **`MarkdownRenderer`** (`com.aiforum.markdown`) — a static `object` (matching the project's
  `TranscriptRenderer` / `LlmResponseParser` idiom). commonmark + `commonmark-ext-gfm-tables`. Soft
  breaks render as `<br>` (forum replies are chatty; single newlines are kept). Results are cached by
  body content — each distinct body is parsed and highlighted once for its lifetime.
- **`CodeHighlighter`** (`com.aiforum.markdown`) — runs **highlight.js' core `highlight()`** (string in,
  `<span class="hljs-…">` HTML out, no DOM) inside a GraalJS context. highlight.js is vendored as the
  `org.webjars:highlightjs` webjar; `highlight.min.js` is read off the classpath and eval'd. A custom
  `FencedCodeBlock` node renderer feeds each fence's text + declared language to it.
- **Degradation is total.** No language, an unknown language, or any highlight failure falls back to a
  plain escaped `<pre><code>`. Highlighting is best-effort, never load-bearing for correctness — so the
  LLM omitting a fence language (despite the prompt asking for one) is a cosmetic miss, not a break.

The view seam: `ReplyView.bodyHtml` (raw `body` is kept for the in-reply-to quote and test assertions),
populated in `Comment.toReplyView()`; the OP body is rendered in `ThreadController.renderThread`. Both
templates emit it via `$unsafe{}`.

## Security — the XSS firewall (do not weaken)

Bodies are **LLM-generated, hence untrusted**, and the templates moved from `${body}` to
`$unsafe{…bodyHtml}`. The thing that makes that safe is **`HtmlRenderer.escapeHtml(true)`**: raw HTML in a
body is rendered inert (shown as text), so the only live HTML in the output is what commonmark and our own
renderers emit. Consequences, by design:

- Tables come from **GFM markdown pipes**, never raw `<table>`.
- A body containing `<script>…</script>` shows the literal text, never executes.

Covered by `tier0/MarkdownRendererTest` and the acceptance scenario "Raw HTML in a reply is escaped". If
you ever want to allow a curated set of raw HTML tags, do it with an allowlist sanitiser (e.g. jsoup) on
the *output* — do not just flip `escapeHtml` off.

## Threading model — single context + lock, and when to grow past it

A GraalJS `Context` is **not thread-safe**, so `CodeHighlighter` serialises every `highlight()` call on a
single lock over **one** lazily-built context. This is deliberate and is the current right choice:

- For a single-user (and small-team) tool, contention is negligible — and because `MarkdownRenderer`
  caches rendered HTML by body content, each block is highlighted exactly **once** for its lifetime, so
  highlighting is off the hot path entirely after first render.

**If you ever outgrow it** (many people hitting code-heavy threads concurrently, *and* the cache cold
often enough that the lock shows up in latency), the fix is a **small pool of contexts** — borrow/return
instead of one-under-a-lock. The trade-off ladder:

| Approach | Complexity | Memory | Under concurrency |
| --- | --- | --- | --- |
| Single context + lock *(current)* | trivial | one JS heap | serialises highlights |
| Pool of N contexts | borrow/return + sizing | N × JS heap | parallel |
| Per-request context | easy to write | spiky | worst — pays warmup every call (don't) |

The change is **isolated to `CodeHighlighter`**: callers only ever see `highlight(code, language)`, so
swapping the single context for a pool moves no other code. Don't reach for per-request contexts — context
warmup is the expensive part, which is exactly why we build one lazily and keep it.

## Theme CSS

`static/hljs-theme.css` is **generated** from the webjar's `github.min.css` / `github-dark.min.css` with
every selector scoped to the app's manual `[data-theme]` toggle (the file header notes how to regenerate
if the webjar version bumps). Block chrome — padding, radius, horizontal scroll, inline-code chips, table
borders — lives in `app.css`, not the generated file. Verified rendering correctly in both themes.

## LLM steer (quality only)

`ProcessLlmClient.FORMATTING` asks personas to write GFM, always declare a fence language, and use
markdown tables rather than raw HTML. This improves the common case; it is **not** relied on for
correctness — see "Degradation is total" above.

## Tests

- `tier0/MarkdownRendererTest` — highlighting, the XSS firewall, GFM tables, soft-breaks, every fallback
  (also the smoke test that highlight.js loads and runs under GraalJS on the JDK 21 toolchain).
- `features/markdown_rendering.feature` — HTTP-level: a fenced block renders highlighted; raw HTML is
  escaped. Uses a docstring LLM step (`the LLM will respond with the markdown:`) and a
  `the reply body does not contain {string}` step, both in `CommonSteps`.
