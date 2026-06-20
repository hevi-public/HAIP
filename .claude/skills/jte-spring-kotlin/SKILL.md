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
| `data-scope` | `branch-only` / `whole-thread` on the composer |

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
