---
name: jte-spring-kotlin
description: Server-side rendering with JTE templates in the AI Forum Spring Boot + Kotlin app — the gg.jte Gradle plugin and precompile config, jte-spring-boot-starter-3 wiring, .kte Kotlin templates taking typed DTO params, calling sub-template fragments, and the stable data-* semantic-hook convention the acceptance tests assert against. Use this whenever creating or editing .kte templates, wiring a controller to a view, configuring JTE in build.gradle.kts, or debugging a template/DTO mismatch. Reach for it before touching anything under src/main/jte so templates compile at build time and stay assertable.
---

# JTE + Spring Boot + Kotlin (SSR) for AI Forum

The UI is server-rendered with JTE (Java Template Engine). The decisive reason we chose JTE over a
runtime template engine: **JTE compiles templates to typed classes against the view-model DTOs**, so a
wrong field name, a missing param, or a type mismatch fails *the build* — no browser needed. That
matters because the dev jail can't run one, and because it turns a class of view bugs into compile
errors the acceptance suite never even has to catch.

Templates render the surfaces the acceptance tests assert against, so they emit **stable `data-*`
hooks** rather than relying on visual CSS classes — see the convention below and
[[cucumber-spring-bdd]].

## Dependencies + Gradle plugin

```kotlin
plugins {
    id("gg.jte.gradle") version "3.2.4"
}

dependencies {
    implementation("gg.jte:jte:3.2.4")
    implementation("gg.jte:jte-spring-boot-starter-4:3.2.4")  // ← starter-4 for Spring Boot 4.x
    implementation("gg.jte:jte-kotlin:3.2.4")                 // enables .kte (Kotlin) templates
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
```

Note the starter is named for the Spring Boot major: `jte-spring-boot-starter-4` for Spring Boot 4,
`-starter-3` for Spring Boot 3. Mismatching it against the wrong Spring Boot major is the usual
"no view resolver" / startup failure.

## Precompile wiring (the load-bearing part)

Generate template sources/classes at build time and make Kotlin compilation depend on it, so template
errors break the build before any test runs:

```kotlin
jte {
    generate()                                  // generate template sources at build time
    contentType.set(gg.jte.ContentType.Html)    // HTML-aware output escaping
}
```

The plugin already wires `compileKotlin` to depend on `generateJte` and adds the generated sources to
the source set — do NOT add `generateJte.dependsOn("compileKotlin")` yourself (that's circular: the
generated sources are compiled *by* compileKotlin). If CI ever shows the generated sources aren't on
the compile path, add the dir explicitly:

```kotlin
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated-sources/jte")) }
```

**Runtime property (the one that bites first):** the JTE Spring Boot starter refuses to create its
`TemplateEngine` unless you tell it where templates come from. With build-time `generate()`, set
**`gg.jte.use-precompiled-templates: true`** so it loads the compiled template classes. Without it the
context fails to start with *"You need to either set gg.jte.usePrecompiledTemplates or
gg.jte.developmentMode to true."* In `application.yml`:

```yaml
gg:
  jte:
    use-precompiled-templates: true   # load build-time compiled templates; no in-process compiler
```

Under `dev` you can instead set `gg.jte.development-mode: true` for hot reload (compiles from
`src/main/jte` at runtime). In the Docker pipeline, run `generateJte` as an explicit early stage so a
template/DTO mismatch fails fast.

## Templates take typed params

Templates live in `src/main/jte/`. Use `.kte` for Kotlin. Each declares its params and imports up
top — this is what gives compile-time safety:

`src/main/jte/thread.kte`:
```kotlin
@import com.aiforum.dto.ThreadViewDTO
@import com.aiforum.dto.ReplyViewDTO
@param page: ThreadViewDTO

<!DOCTYPE html>
<html>
<head><title>${page.title}</title></head>
<body>
  <h1 data-thread-id="${page.id}">${page.title}</h1>
  <section class="tree">
    @for(reply in page.root.children)
      @template.fragments.replyNode(reply = reply)
    @endfor
  </section>
  @template.fragments.composer(composer = page.composer)
</body>
</html>
```

## Calling sub-template fragments

Any template is callable as `@template.<path>.<name>(param = value)`. Keep reusable pieces in
`src/main/jte/fragments/`:

`src/main/jte/fragments/replyNode.kte`:
```kotlin
@import com.aiforum.dto.ReplyViewDTO
@param reply: ReplyViewDTO

<article
    data-reply-id="${reply.id}"
    data-state="${reply.state.name.lowercase()}"
    data-failure-category="${reply.failureCategory?.name ?: ""}"
    data-retryable="${reply.retryable}">

  @if(reply.state.name == "FAILED")
    @template.fragments.errorState(reply = reply)
  @else
    <div class="body">${reply.body}</div>
  @endif

  <span data-vote-count="${reply.voteCount}">+${reply.voteCount}</span>

  @for(child in reply.children)
    @template.fragments.replyNode(reply = child)   <%-- recursion renders the tree --%>
  @endfor
</article>
```

## Shared page shell + htmx (`layout.kte`)

Full pages share one document shell instead of each `.kte` re-inlining `<!DOCTYPE html>`. `layout.kte`
takes the body as a **content block** (`gg.jte.Content`) and renders it inside `<head>`/`<body>`:

```kotlin
@param title: String
@param content: gg.jte.Content
<!DOCTYPE html>
<html lang="en">
<head>
  <title>${title}</title>
  <script src="/webjars/htmx.org/dist/htmx.min.js"></script>   <%-- htmx, see below --%>
</head>
<body>${content}</body>
</html>
```

A page wraps its markup by passing an `@`…`` content block:

```kotlin
@template.layout(title = title, content = @`
  <div class="thread" data-thread-id="${threadId}">…</div>
`)
```

Only **full pages** wrap in the layout. **Fragment** templates (`fragments/replyList`,
`fragments/composer`, a single re-rendered node) stay bare — htmx swaps them into an already-loaded
page, so a `<head>` would be wrong.

**htmx is delivered by webjar, not a CDN** (hermetic, offline, version-pinned like everything else):
`org.webjars.npm:htmx.org` + `org.webjars:webjars-locator-lite` in `build.gradle.kts`; the locator
serves it version-agnostically at `/webjars/htmx.org/dist/htmx.min.js`, so an htmx bump doesn't churn
the `<script src>`.

**The encoding gotcha when htmx drives an existing JSON endpoint.** An htmx form POSTs
`application/x-www-form-urlencoded` by default, but the acceptance suite POSTs JSON to the same
endpoint — one handler can't bind both. Keep the JSON contract green and add a second handler beside
it, discriminated by `consumes`, both delegating to one private method:

```kotlin
@PostMapping("/threads/{id}/generate", consumes = [MediaType.APPLICATION_JSON_VALUE])
fun json(@PathVariable id: String, @RequestBody req: GenerateRequest, model: Model) = respond(id, req, model)

@PostMapping("/threads/{id}/generate", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
fun form(@PathVariable id: String, req: GenerateRequest, model: Model) = respond(id, req, model)  // model-attribute binding
```

A fragment whose composer must keep working *after* a swap needs whatever its composer params require
(here `threadId` + the persona list) added to the model by **every** endpoint that returns it. Thread
those through `fragments/replyList` → `fragments/replyNode` as **nullable-default** params so a render
path that lacks them (e.g. retry) still compiles and just omits the composer.

**The error-fragment view for failed htmx requests (`fragments/errorNotice.kte`).** htmx swaps whatever
a request returns into the fragment's target, so an *uncaught* exception that returns Boot's Whitelabel
error **page** would drop a whole `<html>` document into a fragment slot and corrupt the view (and the
disabled control + spinner would never re-enable). So a small bare fragment is rendered instead:

```kotlin
@param status: Int = 500
@param message: String = "Something went wrong on the server."
<div class="error-notice" role="alert" aria-live="assertive"
     data-error-fragment="server"
     data-error-status="${status}">
  <span class="error-notice__icon" aria-hidden="true">⚠</span>
  <span class="error-notice__text">${message}</span>
</div>
```

It's a **bare fragment** — no `layout.kte`, no `<head>` — so htmx drops it cleanly into the swap target
(the "only full pages wrap in the layout" rule above). `web/HtmxErrorAdvice` (a `@ControllerAdvice`)
renders it: it returns the view name `"fragments/errorNotice"` (resolved by the JTE ViewResolver like
any other) but **sets a real HTTP status on the response first** (502/503/504/500 per exception) rather
than letting the view name render a bare 200 — the non-2xx is what fires the client's
`htmx:responseError`, re-enables the stuck control, and lets the acceptance suite read the status. The
stable **`data-error-fragment="server"` hook** — not the copy or the CSS — is what the steps assert on,
per the data-* convention below; the advice only fires for `HX-Request` calls, so a non-htmx request
keeps Boot's default page (see [[cucumber-spring-bdd]] for the failure-path acceptance wiring).

## Static assets & styling (`static/`, `app.css`)

The visual layer is hand-written CSS + a little vanilla JS served as **static resources** — no build
step, no framework. Spring Boot serves `src/main/resources/static/**` at the web root with zero config:

- `src/main/resources/static/app.css` → `/app.css`; `app.js` → `/app.js`.
- `layout.kte` links them in `<head>` (`<link rel="stylesheet" href="/app.css">`,
  `<script src="/app.js" defer>`); every full page inherits them through the shell.
- `app.js` re-binds on `htmx:afterSwap` so behaviour (composer auto-grow) survives htmx swaps.

**Style with `class=`, never with `data-*`.** The probe (`Html.kt`) reads attributes by **regex off the
single opening tag** and substring-matches text, so when styling templates:

- *Safe:* add `class=`, add child elements/wrappers, change visible chrome text, wrap a title in `<a>`.
- *Unsafe:* move/rename a `data-*` attribute, split the composer `<form>` (its `data-*` + `hx-*` must
  stay on **one** opening tag), or drop a field the steps assert (`name="text"`, `name="personaIds"`,
  `value="SUMMON"`).
- Drive state/error/empty visuals off the **existing** hooks — `article.reply[data-state="failed"]`,
  `[data-failure-category="RATE_LIMITED"]`, `[data-empty-state="waiting"]` — so the six error states
  need no new markup.

**Design source of truth:** the six full-screen comps in `HAIP_design/*.dc.html` (the sage `#b3bca3`
HUP-lineage system, Verdana prose + mono chrome) — **not** the `Style Tile`'s olive exploration. The
palette/type/spacing tokens live as CSS custom properties at the top of `app.css`.

## The data-* semantic-hook convention

Acceptance assertions must target stable, behavioural attributes — not CSS classes, which churn with
styling. Standardize on:

| Hook | Meaning |
|------|---------|
| `data-reply-id`, `data-thread-id` | entity identity |
| `data-state` | `drafting` / `posted` / `failed` / `cancelled` |
| `data-failure-category` | `FAILED_RETRY` / `RATE_LIMITED` / `COULDNT_SAVE` / … |
| `data-retryable` | `true` / `false` |
| `data-retry-after` | seconds, present only for rate-limit |
| `data-vote-count` | the firewalled `+1` tally (visible to owner) |
| `data-scope` | `BRANCH_ONLY` / `WHOLE_THREAD` on the composer (the `ScopeMode` enum name) |
| `data-error-fragment` | `server` on the htmx error notice (`fragments/errorNotice.kte`); the hook the failure-path steps assert on |

This keeps the same `.feature` files re-pointable at a Playwright layer later — the hooks survive a
visual redesign.

## JTE syntax cheat-sheet

```kotlin
@param x: Type                 <%-- declare a param --%>
@import some.Type              <%-- import --%>
${expr}                        <%-- HTML-escaped output --%>
$unsafe{expr}                  <%-- raw, only for trusted HTML --%>
!{val y = expr;}               <%-- run code --%>
@if(c) … @elseif(c) … @else … @endif
@for(i in list) … @endfor
@template.path.name(p = v)     <%-- call another template --%>
<%-- comment --%>
```

Null-safety is Kotlin's: use `?.`, `?:`, and the elvis fallback inside `${}`.

## Wiring a controller to a view

With `jte-spring-boot-starter-3`, return the template name (path under `src/main/jte`, no extension)
and put the DTO on the model under the param name:

```kotlin
@Controller
class ThreadController(private val threads: ThreadService) {
    @GetMapping("/threads/{id}")
    fun thread(@PathVariable id: String, model: Model): String {
        model.addAttribute("page", threads.view(id))   // matches @param page
        return "thread"                                 // → src/main/jte/thread.kte
    }
}
```

For HTMX-style fragment responses (e.g. a single re-rendered reply node after retry), render a
fragment template directly and return the HTML.

## Common failure points

- **Param name mismatch** between `model.addAttribute("page", …)` and `@param page` → render error.
  The names must match exactly.
- **`.kte` not recognized** → missing `gg.jte:jte-kotlin`.
- **Generated sources not compiled in CI** → add the generated dir to the source set (above) and run
  `generateJte` before `compileKotlin`/tests.
- **Asserting on CSS classes** → brittle; switch to `data-*` hooks.

## Verify

- `./gradlew generateJte compileKotlin` succeeds and fails loudly on a deliberate bad field
  reference (that failure *is* the feature working).
- Hitting a controller route renders the `.kte` with the DTO; acceptance steps find the expected
  `data-*` hooks in the HTML.
