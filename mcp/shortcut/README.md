# Shortcut MCP server (read-only)

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes the
[Shortcut](https://www.shortcut.com) ticketing system to MCP clients (Claude Code, Claude
Desktop, etc.).

**Read-only by design.** Every tool is a `GET` against the Shortcut REST API (v3) and is
tagged with `readOnlyHint`. This server cannot create, update, or delete anything in
Shortcut. Write capabilities can be added later.

## Tools

| Tool | Shortcut endpoint | What it does |
|------|-------------------|--------------|
| `get_current_member` | `GET /member` | Who the API token belongs to ("who am I") |
| `list_members` | `GET /members` | All workspace members |
| `get_member` | `GET /members/{uuid}` | A single member by UUID |
| `search_stories` | `GET /search/stories` | Search stories with Shortcut's operator syntax (paginated) |
| `get_story` | `GET /stories/{id}` | Full detail of one story |
| `list_epics` | `GET /epics` | All epics |
| `get_epic` | `GET /epics/{id}` | One epic + stats |
| `list_epic_stories` | `GET /epics/{id}/stories` | Stories within an epic |
| `list_iterations` | `GET /iterations` | All iterations (sprints) |
| `get_iteration` | `GET /iterations/{id}` | One iteration + stats |
| `list_iteration_stories` | `GET /iterations/{id}/stories` | Stories within an iteration |
| `list_workflows` | `GET /workflows` | Workflows + state ids (maps "In Progress" → numeric state) |
| `list_groups` | `GET /groups` | Teams |
| `list_objectives` | `GET /objectives` | Objectives (formerly Milestones) |
| `list_labels` | `GET /labels` | Labels |
| `list_projects` | `GET /projects` | Projects (legacy grouping) |

### Search syntax

`search_stories` accepts Shortcut's [search operators](https://help.shortcut.com/hc/en-us/articles/360000046646-Searching-in-Shortcut),
e.g. `owner:jane state:"In Progress" type:bug is:started`. When a response includes a
`next` token, pass it back as the `next` argument to page through results.

## Configuration

| Env var | Required | Default | Notes |
|---------|----------|---------|-------|
| `SHORTCUT_API_TOKEN` | yes | — | Create one at <https://app.shortcut.com/settings/account/api-tokens> |
| `SHORTCUT_BASE_URL` | no | `https://api.app.shortcut.com/api/v3` | Override the API base URL |

The token is sent in the `Shortcut-Token` header on every request.

## Build & run

```bash
cd mcp/shortcut
npm install
npm run build          # → dist/
npm test               # node:test unit tests (pure helpers)

SHORTCUT_API_TOKEN=xxxxxxxx npm start
```

The server speaks MCP over **stdio**. `stdout` is the protocol channel — all logging goes
to `stderr`.

## Register with Claude Code

```bash
cd mcp/shortcut && npm install && npm run build

claude mcp add shortcut \
  --env SHORTCUT_API_TOKEN=xxxxxxxx \
  -- node /absolute/path/to/mcp/shortcut/dist/index.js
```

Or add it to an MCP client config (`claude_desktop_config.json` and friends) directly:

```json
{
  "mcpServers": {
    "shortcut": {
      "command": "node",
      "args": ["/absolute/path/to/mcp/shortcut/dist/index.js"],
      "env": { "SHORTCUT_API_TOKEN": "xxxxxxxx" }
    }
  }
}
```

## Layout

```
mcp/shortcut/
  src/
    index.ts        stdio entry point — reads env, wires the server, connects transport
    client.ts       read-only Shortcut REST client (fetch + typed errors)
    tools.ts        the 16 read-only tool definitions
    client.test.ts  node:test unit tests for the pure helpers
```

## References

- Shortcut REST API v3: <https://developer.shortcut.com/api/rest/v3>
- API tokens: <https://help.shortcut.com/hc/en-us/articles/205701199-Shortcut-API-Tokens>
- MCP TypeScript SDK: <https://github.com/modelcontextprotocol/typescript-sdk>
