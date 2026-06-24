#!/usr/bin/env node
/**
 * Shortcut MCP server — read-only.
 *
 * Exposes the Shortcut ticketing system (https://www.shortcut.com) to MCP
 * clients over stdio. Only GET endpoints are wired up; this server cannot
 * create, update, or delete anything in Shortcut.
 *
 * Configuration (environment variables):
 *   SHORTCUT_API_TOKEN   (required)  Personal API token from
 *                                    https://app.shortcut.com/settings/account/api-tokens
 *   SHORTCUT_BASE_URL    (optional)  Override the API base URL.
 *
 * NOTE: stdout is the MCP protocol channel — never write logs there. All
 * diagnostics go to stderr.
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { DEFAULT_BASE_URL, ShortcutClient } from "./client.js";
import { registerShortcutTools } from "./tools.js";

async function main(): Promise<void> {
  const token = process.env.SHORTCUT_API_TOKEN?.trim();
  if (!token) {
    console.error(
      "FATAL: SHORTCUT_API_TOKEN is not set. Create a token at " +
        "https://app.shortcut.com/settings/account/api-tokens and export it as SHORTCUT_API_TOKEN.",
    );
    process.exit(1);
  }

  const baseUrl = process.env.SHORTCUT_BASE_URL?.trim() || DEFAULT_BASE_URL;
  const client = new ShortcutClient(token, baseUrl);

  const server = new McpServer({
    name: "shortcut",
    version: "0.1.0",
  });

  registerShortcutTools(server, client);

  const transport = new StdioServerTransport();
  await server.connect(transport);

  console.error(`shortcut-mcp ready (read-only) — base URL ${baseUrl}`);
}

main().catch((err) => {
  console.error("shortcut-mcp failed to start:", err);
  process.exit(1);
});
