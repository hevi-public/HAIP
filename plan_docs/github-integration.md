# GitHub integration — read-only `gh`, for humans and personas

Status: **shipped** (2026-06).

Goal: bring live GitHub data into the project, **read-only**, for the two consumers that want it — the
owner browsing the forum, and the AI personas reasoning mid-generation — each through the integration
that actually fits it. Nothing in this feature can mutate a repository; read-only is enforced
structurally, not by trusting a prompt or a reviewer.

## Three pieces, two consumers

There are two distinct consumers and they need different plumbing. Conflating them is the trap this design
avoids.

| | Forum `/github` page (humans) | Personas (the model, mid-generation) |
|---|---|---|
| Consumer | Browser user | `claude -p`, while drafting a reply |
| Path | Spring → `gh` → JTE page | `claude -p --mcp-config` → gh-readonly MCP server → `gh` |
| Uses the MCP server? | **No** | **Yes** — that is what MCP is for |
| Default | Off (`aiforum.github.enabled`) | Off (`aiforum.llm.github-tools-enabled`) |

### 1. `gh-readonly` MCP server (`mcp/gh-readonly/`)

A zero-dependency, pure-ESM stdio MCP server (matches the repo's "no runtime deps" front-end convention)
that exposes `gh` to MCP clients as read-only tools: `repo`/`issue`/`pr`/`release`/`run` view+list,
`search`, and a GET-only `gh api` passthrough. It is **not** human-facing — MCP is a tool protocol for LLM
callers (Claude Code, `claude -p --mcp-config`), with no URL or UI.

Registered for this repo via project-scoped `.mcp.json`, so Claude Code picks it up. Covered by `node:test`
(`npm run test:mcp`).

### 2. The human `/github` page (Option A)

`GET /github` renders a server-side snapshot of a configured repo (summary + open PRs + open issues),
fetched through the `GitHubClient` seam. This is the **backend's own** path to GitHub — it shells out to
`gh` directly and does **not** go through the MCP server. Off by default; with the flag off, or `gh`
missing/unauthenticated, or the repo unreachable, the page renders a clear off-state rather than erroring.

### 3. Personas call the gh tools (Option B)

When enabled, `ProcessLlmClient` hands the spawned `claude -p` the gh-readonly MCP server via
`--mcp-config <path> --strict-mcp-config` and pre-authorises `mcp__<server>` in `--allowedTools` — the same
mechanism the existing WebFetch pre-authorisation uses (headless mode can't prompt for tool permission, so
an un-authorised tool is silently denied). cli-provider only; the OpenAI path has no tool loop.

## Read-only is enforced, not promised

The whole point is that none of this can write to GitHub. Two independent guards, in two languages:

- **MCP server** (`mcp/gh-readonly/tools.mjs`): an allowlist of fixed argv builders (no write subcommand
  exists), `execFile` with no shell (no injection; any input starting with `-` is rejected), and an
  `assertReadOnly` guard re-checked on every argv (mutating verbs banned anywhere; `gh api` forced to an
  explicit `--method GET`; the GraphQL endpoint and request-body fields `-F`/`--field`/`--input` rejected).
- **Backend adapter** (`github/GhCliGitHubClient.kt`): only ever builds `repo view` / `pr list` /
  `issue list`, re-checked by a `requireReadOnly` guard, and inert unless explicitly enabled.

## The seam (mirrors the LlmClient split)

`GitHubClient` is the narrow interface; the un-fakeable IO and the pure parsing are split so the irreducible
part stays tiny — exactly like `LlmClient` / `ProcessLlmClient` / `LlmResponseParser`:

- `github/GitHubClient.kt` — the interface + view types + `GitHubResult` (`Ok` | `Unavailable`).
  `Unavailable` is a first-class, user-facing state, not an error.
- `github/GitHubJson.kt` — **Tier 0**, pure: parse `gh ... --json` envelopes into the view types.
- `github/GhCliGitHubClient.kt` — **Tier 1**, the `gh` adapter: spawning, stream capture, the deadline,
  error→`Unavailable` mapping, behind an overridable `exec` seam. Inert (no spawn) unless
  `aiforum.github.enabled=true`, so the bean is safe in every context and the test profile never shells out.

### `gh` availability — UI vs log

A missing/broken `gh` is reported two ways, deliberately split:

- **UI** is driven by the *live per-call* result: the real fetch already spawns `gh`, so a missing binary
  (ENOENT) or an auth failure (non-zero `gh repo view`) becomes `Unavailable` on the next page load — never
  stale, and free (no extra probe). This is the source of truth.
- **Log** adds the operator's view: a WARN on every per-call failure (the live signal), plus a **one-time
  startup probe** (`gh --version`) that WARNs at boot — but only when the integration is **enabled**, so a
  disabled feature stays silent. No per-call `gh --version` probe (wasteful) and no startup-only verdict
  feeding the UI (would go stale if `gh` is installed, or its auth changes, after boot).

These logs follow the project's tested structured-event convention (`gh.unavailable`, `gh.startup.ok`,
`gh.startup.unavailable`, `gh.list.failed`) — the gh seam is one of its first two adopters. Convention,
status, and deferred follow-ups (JSON sink, id registry, remaining seams):
[`plan_docs/tested-structured-logging.md`](tested-structured-logging.md).
- `web/GitHubController.kt` + `jte/github.kte` — the page; untrusted `gh` text (PR titles, author logins)
  is HTML-escaped via JTE `${}`, so it's display-only and not an injection vector.

## ⚠ Security caveats

- **Untrusted content / prompt injection (Option B).** GitHub issue/PR/comment bodies are arbitrary
  attacker-controllable text — the same risk class as WebFetch. The Docker jail (requirements §12) meant to
  isolate the spawned CLI from the host isn't built yet, so this stays **off by default**.
- **Read scope follows host auth.** The MCP server is read-only, so a persona can never *mutate* the repo;
  but it can *read* whatever the host's `gh` is authenticated to see. Scope that `gh auth` deliberately.
- **The /github page is display-only**, escaped, and never feeds the model — so it carries none of the
  Option-B injection risk.

## Configuration

```yaml
aiforum:
  github:                    # Option A — the human /github page (does NOT use the MCP server)
    enabled: false           # true => fetch live data from `gh`; false => /github shows the off state
    repo: ""                 # "OWNER/REPO" to pin (recommended — the app's working dir isn't a clone)
    command: gh
    list-limit: 10           # max open PRs / issues to list (clamped 1..100)
    timeout-seconds: 20
  llm:                       # Option B — personas call the gh tools (cli provider only)
    github-tools-enabled: false
    github-mcp-config: ""    # passed to `claude --mcp-config`; e.g. an ABSOLUTE path to this repo's .mcp.json
    github-mcp-server-name: gh-readonly   # must match the server key in that config; authorises mcp__<name>
```

Both options require `gh` installed and authenticated (`gh auth login`) on the host.

## Test coverage

- **MCP server**: `node:test` — argv construction, input validation, the read-only guard, and a stdio
  protocol smoke test (`npm run test:mcp`).
- **Tier 0** `GitHubJsonTest` — pure `gh --json` parsing.
- **Tier 1** `GhCliGitHubClientTest` — argv/read-only invariant + error mapping with the `exec` seam
  substituted; the real spawn-failure path covered with a bogus binary (no real `gh`, no network).
- **Tier 1** `ProcessLlmClientTest` — the `--mcp-config` / `mcp__<server>` wiring (disabled,
  blank-config-inert, enabled-mounts-strictly, server-name-follows-config, composes-with-web-fetch).
- **Tier 2** `GitHubControllerTest` — Ok→page / Unavailable→off-state mapping with a fake seam.
- **Acceptance** `github_page.feature` (scriptable `GitHubClient` seam) + `site_nav.feature` (the header
  github link).
