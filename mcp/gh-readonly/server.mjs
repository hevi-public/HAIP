#!/usr/bin/env node
// Read-only GitHub CLI (`gh`) MCP server.
//
// A zero-dependency, stdio JSON-RPC 2.0 MCP server that exposes a curated set
// of READ-ONLY `gh` commands plus a GET-only `gh api` passthrough. It never
// runs a shell and never builds a mutating `gh` invocation (see tools.mjs for
// the per-tool builders and the `assertReadOnly` guard).
//
// Transport: newline-delimited JSON-RPC over stdin/stdout (the MCP stdio
// transport). Diagnostics go to stderr only — stdout carries protocol frames.

import { execFile } from 'node:child_process';
import { createInterface } from 'node:readline';
import { buildGhArgs, TOOLS } from './tools.mjs';

const SERVER_INFO = { name: 'gh-readonly', version: '1.0.0' };
const DEFAULT_PROTOCOL_VERSION = '2025-06-18';
const GH_TIMEOUT_MS = Number(process.env.GH_MCP_TIMEOUT_MS || 60_000);
const GH_MAX_BUFFER = 10 * 1024 * 1024; // 10 MB of gh output

function log(...args) {
  process.stderr.write(`[gh-readonly] ${args.join(' ')}\n`);
}

// Run `gh` with a fixed argv array (no shell). Resolves with combined output.
function runGh(argv) {
  return new Promise((resolve) => {
    execFile(
      'gh',
      argv,
      {
        timeout: GH_TIMEOUT_MS,
        maxBuffer: GH_MAX_BUFFER,
        env: { ...process.env, GH_PAGER: 'cat', PAGER: 'cat', GH_PROMPT_DISABLED: '1' },
      },
      (err, stdout, stderr) => {
        if (err) {
          if (err.code === 'ENOENT') {
            resolve({ ok: false, text: "`gh` CLI not found on PATH. Install it from https://cli.github.com and run `gh auth login`." });
            return;
          }
          if (err.killed) {
            resolve({ ok: false, text: `gh timed out after ${GH_TIMEOUT_MS}ms` });
            return;
          }
          const detail = (stderr || stdout || err.message || '').toString().trim();
          resolve({ ok: false, text: `gh exited with an error:\n${detail}` });
          return;
        }
        const out = (stdout || '').toString();
        resolve({ ok: true, text: out.trim().length ? out : '(no output)' });
      },
    );
  });
}

// ---- JSON-RPC plumbing ----------------------------------------------------

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function result(id, value) {
  send({ jsonrpc: '2.0', id, result: value });
}

function error(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

async function handle(msg) {
  // Notifications (no id) get no response.
  const isNotification = msg.id === undefined || msg.id === null;
  const { id, method, params } = msg;

  switch (method) {
    case 'initialize': {
      const requested = params?.protocolVersion;
      result(id, {
        protocolVersion: typeof requested === 'string' ? requested : DEFAULT_PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: SERVER_INFO,
        instructions:
          'Read-only GitHub CLI access. All tools wrap `gh` and cannot mutate anything. ' +
          'Requires `gh` installed and authenticated (`gh auth login`).',
      });
      return;
    }

    case 'notifications/initialized':
    case 'initialized':
      return; // notification, nothing to send

    case 'ping':
      if (!isNotification) result(id, {});
      return;

    case 'tools/list':
      result(id, {
        tools: TOOLS.map((t) => ({
          name: t.name,
          description: t.description,
          inputSchema: t.inputSchema,
        })),
      });
      return;

    case 'tools/call': {
      const name = params?.name;
      const input = params?.arguments ?? {};
      let argv;
      try {
        argv = buildGhArgs(name, input);
      } catch (e) {
        // Invalid input / disallowed request -> a tool error result, not a protocol error.
        result(id, { content: [{ type: 'text', text: `Error: ${e.message}` }], isError: true });
        return;
      }
      const { ok, text } = await runGh(argv);
      result(id, { content: [{ type: 'text', text }], isError: !ok });
      return;
    }

    default:
      if (!isNotification) error(id, -32601, `Method not found: ${method}`);
      return;
  }
}

// ---- Read newline-delimited JSON-RPC from stdin ---------------------------

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    error(null, -32700, 'Parse error');
    return;
  }
  Promise.resolve(handle(msg)).catch((e) => {
    log('handler error:', e?.message || String(e));
    if (msg && msg.id !== undefined && msg.id !== null) {
      error(msg.id, -32603, 'Internal error');
    }
  });
});

rl.on('close', () => process.exit(0));

log(`ready — ${TOOLS.length} read-only tools`);
