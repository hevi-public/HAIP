# Audit Backlog — Deferred (Tier 3) & By-Design (Tier 4)

**Status:** documented, not scheduled. **Date:** 2026-06-25. **Context:** full-project audit run against
the codebase while it is a **single-user, local-only PoC**. Many findings that an audit ranks
"Critical/High" assume a hostile network and multiple users — neither exists yet. This file records those
findings so they are not lost, each with the **trigger** that should promote it back into active work.

The actionable items (Tier 1 / Tier 2) live in [audit-remediation-tier1-tier2.md](audit-remediation-tier1-tier2.md).

---

## Tier 3 — Defer until this stops being single-user / local

Real findings, wrong time. Do **not** spend PoC budget here. Each has a concrete promotion trigger.

### T3.1 — Authentication & authorization (the whole cluster) — *Critical when triggered*
- **Finding:** No Spring Security on the classpath, no `SecurityFilterChain`, no auth/authn/authz anywhere.
  Every mutating + admin endpoint is public; the "owner" identity is a hardcoded server-side string
  (`authorId = "owner"`). IDOR on every id/slug-addressed mutation (delete/edit any thread, comment,
  persona; vote; star; regenerate). No CSRF tokens.
- **Why deferred:** single user on their own machine. There is no second principal to impersonate and no
  untrusted client. The free, sufficient mitigation for *now* is the one-line loopback bind handled in
  Tier 2 (T2.1) — that removes the network exposure without building an auth layer.
- **Promotion trigger:** the moment the app is exposed beyond loopback, gains a second user, or is
  deployed to any shared/hosted environment.
- **Scope when promoted:** add `spring-boot-starter-security`; a `SecurityFilterChain` gating `/admin/**`
  and all mutating routes; a real owner principal (form/basic login or reverse-proxy auth); ownership
  columns so IDOR checks have something to check; CSRF tokens on the htmx POST forms
  (htmx `hx-headers` / meta-tag pattern).

### T3.2 — Paid-LLM endpoint abuse: cost-amplification & DoS — *High when triggered*
- **Finding:** `/generate`, `/regenerate`, `/auto-grow`, `/compose`, `/attachments/{id}/describe` are
  unauthenticated and each triggers a paid/expensive LLM call. Unbounded callers = cost blow-up + DoS.
- **Why deferred:** the only caller is the owner, who is already paying and is rate-limited by their own
  clicking. The unbounded-pool concern that *does* bite a single user (resource starvation, not cost) is
  handled separately in Tier 2 (T2.3).
- **Promotion trigger:** same as T3.1 (any untrusted caller) — pairs with the auth work.
- **Scope when promoted:** per-principal rate limiting / quota on the LLM-triggering routes, behind auth.

### T3.3 — Second-order prompt injection via owner reply-edit — *Medium/High when triggered*
- **Finding:** `POST /replies/{id}/edit` lets a caller rewrite any persona reply body, which then re-seeds
  future LLM context in that branch — a stored prompt-injection vector, not just a display change.
- **Why deferred:** the only caller is the owner editing their own forum; injecting into one's own context
  is self-inflicted, not a vulnerability, until other users can write replies that the owner's personas read.
- **Promotion trigger:** multi-user write access (other users posting/editing in shared threads).
- **Scope when promoted:** gate edit behind ownership (T3.1), and treat cross-author body text as untrusted
  when assembling context (the markdown XSS firewall already handles the render side).

### T3.4 — Prod web-fetch open to any host, no sandbox — *Medium when triggered*
- **Finding:** `application-prod.yml` ships `web-fetch-enabled: true` with `web-fetch-allowed-domains: ""`
  (= any host), and the documented Docker jail (requirements §12) is not built. If web-fetch is on,
  personas fetch arbitrary untrusted web content (prompt-injection surface) directly on the host.
- **Why deferred:** the feature is effectively off in normal single-user use; the blast radius is the
  owner's own machine, which the owner already controls.
- **Promotion trigger:** enabling web-fetch in any exposed/hosted deployment, OR shipping a prod artifact
  others run.
- **Scope when promoted:** default the prod allowlist to a non-empty trusted set; build the sandbox/jail
  before enabling web-fetch off-loopback; treat fetched content as untrusted in context assembly.

### T3.5 — Supply-chain hardening — *Medium when triggered*
- **Findings:** no Gradle dependency verification / lockfile + dynamic version coordinates
  (`webjars-locator-lite`, `jackson-module-kotlin`, `kotlin-reflect` unversioned via BOM); Docker base
  image floats (`eclipse-temurin:21-jdk`, no digest); Node installed via `curl … | bash -`; the committed
  `gradle-wrapper.jar` does not match the canonical Gradle 9.5.0 hash and `distributionSha256Sum` is unset.
- **Why deferred:** these protect a *released, reproducible* artifact consumed by others. A PoC built and
  run by one developer on one machine has a trusted local toolchain.
- **Promotion trigger:** first real release / publish / shared CI artifact, or onboarding a second
  contributor who builds from clean.
- **Scope when promoted:** commit `gradle/verification-metadata.xml` (sha256) or enable dependency locking;
  pin the Docker base by digest; pin/verify the NodeSource install; regenerate the wrapper from a trusted
  Gradle and set `distributionSha256Sum`. *(Cheap exception already pulled into Tier 2: pinning the base
  image by digest the next time the Dockerfile is touched.)*

### T3.6 — CI/release hygiene — *Low/Medium when triggered*
- **Findings:** MCP subprojects (`mcp/shortcut`, `mcp/gh-readonly`) are never built or tested in CI;
  `acceptance` passes with zero discovered scenarios (`failOnNoDiscoveredTests = false`); no `permissions:`
  block on `GITHUB_TOKEN`; Docker build runs as root; no release/versioning pipeline (`0.0.1-SNAPSHOT`
  hardcoded, no SBOM/signing).
- **Why deferred:** production-CI and provenance concerns; nothing is released yet.
- **Promotion trigger:** first release, or when the MCP servers become load-bearing enough that silent rot
  would hurt.
- **Scope when promoted:** add an `npm ci && build && test` CI job for the MCP subprojects; flip
  `failOnNoDiscoveredTests = true`; add `permissions: contents: read`; add a non-root `USER`; introduce a
  tagged release workflow with SBOM/signing.

---

## Tier 4 — By-design / skip for now

Not bugs. These are maintainability debt or deliberate, well-instrumented trade-offs. Touch only when they
actually start to hurt — premature work here is pure cost.

### T4.1 — `GenerationService` god object (~493 LOC, ~5 responsibilities)
Owns summon, async fan-out, sync generate, auto-grow, retry/regenerate, owner-comment persistence, persona
resolution, context assembly, attachment-map building. **Refactor trigger:** when adding a feature here
means reading the whole file to be safe, extract a `ContextBuilder` and a `RevisionService`. Until then the
seam discipline around it (single `LlmClient` boundary) keeps the blast radius contained.

### T4.2 — Owner-node creation duplicated into controllers
`GenerationController.postOwnerNode` vs `GenerationService.ownerComment` build the same node shape twice;
`ThreadController` also persists rows directly. **Refactor trigger:** the next time owner-node shape
changes (and you'd have to edit both) — move it into the service and call from both controllers.

### T4.3 — `app.css` is a 1418-line monolith
No layering / component split. Fine for SSR; makes dead-rule detection hard. **Refactor trigger:** when a
style change has unclear blast radius or dead rules accumulate — introduce `@layer` / component files.

### T4.4 — Router recovers the LLM's choice by regex name-matching free text
`PersonaRouter` word-matches roster names in the model's prose and silently widens to the whole room on a
parse miss. This is the system's main correctness soft-spot — **but it is already documented and metered**
(`routing_event` + `/admin/stats` parse-miss rate). The instrumentation *is* the correct PoC posture: you
measure the miss rate before paying for structured/tool output. **Promotion trigger:** the measured
parse-miss rate gets high enough to matter — then switch to a numbered-menu prompt or structured/tool
output. Do not pre-optimize.

### T4.5 — Misc premature-hardening items
- Enum-ish TEXT columns (`comment.state`, `failure_category`, `caption_state`) validated only in Kotlin on
  read, no DB `CHECK`. Defense-in-depth; the read-path `enumValueOf` already fails loudly.
- Attachment blobs intentionally leaked on delete ("future dedup-aware GC"). Bounded disk growth, known.
- `recentPosted` / `starredQuery` do `ORDER BY created_at DESC` with no supporting index — full scan, but
  irrelevant at PoC volume. Add an index the day a rail feels slow.
- No `@ControllerAdvice` *fallback* page hardening beyond the htmx-fragment fix in Tier 1 — broader error
  taxonomy can wait.

**Skip rationale (all of T4):** every item is real but is either contained by existing seams/instrumentation
or only matters at a scale/age the project hasn't reached. Revisit at the trigger, not before.

---

## What was clean (no action, ever-green — recorded so it isn't re-litigated)
XSS/output encoding (`escapeHtml(true)` + JTE auto-escape, 3 audited `$unsafe{}` sinks), SQL injection
(fully parameterized incl. recursive CTEs), path traversal / image SSRF (content-addressed store, magic-byte
sniffing, EXIF strip, 10 MiB cap), command injection (argv arrays both subprocess seams), secrets (none
hardcoded, key never logged), the LLM IO seam design, profile/DB isolation (`ProfileGuard`), and the tiered
test architecture. These are strengths to preserve, not work to do.
