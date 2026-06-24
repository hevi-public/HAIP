# `gh-readonly` — read-only GitHub CLI MCP server

A tiny, **zero-dependency** [MCP](https://modelcontextprotocol.io) server that
exposes the [GitHub CLI](https://cli.github.com) (`gh`) to an MCP client as a
set of **read-only** tools. It can list and view repos, issues, PRs, releases,
and Actions runs, search GitHub, and make GET requests to the REST API — and it
**cannot** create, edit, close, merge, comment, or otherwise mutate anything.

Pure ESM, Node ≥ 18, no `npm install` (matches this repo's "no runtime deps"
convention). The server shells out to your already-authenticated `gh`, so it
inherits your existing `gh auth login` credentials and host config.

## How read-only is enforced

This is the whole point of the server, so it is guaranteed in three independent
layers — not by trusting a prompt:

1. **Allowlist, not denylist.** Each tool builds a *fixed* `gh` argv from
   validated input. There is no code path that constructs a write subcommand.
2. **No shell.** Commands run via `execFile('gh', argv)` — never a shell string —
   so input can't be smuggled in through quoting/`;`/`$()`. Any input value that
   begins with `-` (a flag) is rejected.
3. **A defence-in-depth guard.** Every constructed argv is re-checked by
   `assertReadOnly()` before it reaches `gh`: the top-level command and
   subcommand must be on the read allowlist, mutating verbs
   (`create`/`delete`/`merge`/…) are banned anywhere in the argv, and `gh api`
   is forced to an explicit `--method GET` with the GraphQL endpoint and any
   request-body field (`-F`/`--field`/`--input`) rejected.

## Tools

| Tool | `gh` it runs | Notes |
|------|--------------|-------|
| `gh_repo_view` | `gh repo view [repo]` | Repo metadata |
| `gh_issue_list` | `gh issue list` | Filters: state, limit, labels, assignee, author, search |
| `gh_issue_view` | `gh issue view <issue>` | `comments: true` to include comments |
| `gh_pr_list` | `gh pr list` | Filters: state, limit, labels, author, base, search |
| `gh_pr_view` | `gh pr view [pr]` | `comments: true` to include comments |
| `gh_pr_diff` | `gh pr diff [pr]` | The PR diff |
| `gh_pr_checks` | `gh pr checks [pr]` | CI check status |
| `gh_release_list` | `gh release list` | |
| `gh_release_view` | `gh release view [tag]` | Defaults to latest |
| `gh_run_list` | `gh run list` | Filters: limit, branch, workflow, status |
| `gh_run_view` | `gh run view <run>` | `log: true` for the full log |
| `gh_search` | `gh search <type> <query>` | `type`: repos/issues/prs/code/commits |
| `gh_api_get` | `gh api --method GET <endpoint>` | GET-only REST; `params` → query string |

Every tool takes an optional `repo: "OWNER/REPO"`; omit it to use the current
directory's repository.

## Prerequisites

- Node ≥ 18 (uses only the standard library)
- `gh` on your `PATH`, authenticated: `gh auth login`

If `gh` is missing or unauthenticated, tool calls return a clear, non-fatal
error message rather than crashing the server.

## Use with Claude Code

A project-scoped [`.mcp.json`](../../.mcp.json) at the repo root already
registers this server, so Claude Code picks it up automatically in this repo
(approve it once when prompted). To register it globally instead:

```bash
claude mcp add gh-readonly -- node /absolute/path/to/mcp/gh-readonly/server.mjs
```

## Use with any MCP client

It speaks newline-delimited JSON-RPC 2.0 over stdio (the MCP stdio transport).
Point any client's stdio server config at:

```json
{
  "command": "node",
  "args": ["mcp/gh-readonly/server.mjs"]
}
```

## Configuration

| Env var | Default | Purpose |
|---------|---------|---------|
| `GH_MCP_TIMEOUT_MS` | `60000` | Per-command timeout for `gh` |

## Tests

```bash
node --test "mcp/gh-readonly/test/**/*.test.mjs"
```

`tools.test.mjs` covers argv construction, input validation, and the read-only
guard (no `gh` needed). `protocol.test.mjs` drives the server over stdio to
check `initialize` / `tools/list` / `tools/call` framing.
