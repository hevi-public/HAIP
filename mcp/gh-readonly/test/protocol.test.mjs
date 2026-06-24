import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SERVER = fileURLToPath(new URL('../server.mjs', import.meta.url));

// Drive the server over stdio with a batch of requests and collect the
// newline-delimited JSON-RPC responses keyed by id.
function rpc(requests) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [SERVER], { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '';
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error('server did not respond in time'));
    }, 10_000);

    child.stdout.on('data', (d) => {
      out += d.toString();
      const lines = out.split('\n').filter((l) => l.trim());
      const byId = new Map();
      for (const line of lines) {
        try {
          const msg = JSON.parse(line);
          if (msg.id !== undefined) byId.set(msg.id, msg);
        } catch {
          /* partial line */
        }
      }
      // Resolve once every request with an id has a matching response.
      const wantIds = requests.filter((r) => r.id !== undefined).map((r) => r.id);
      if (wantIds.every((wid) => byId.has(wid))) {
        clearTimeout(timer);
        child.kill();
        resolve(byId);
      }
    });
    child.on('error', reject);

    for (const r of requests) child.stdin.write(JSON.stringify(r) + '\n');
  });
}

test('initialize returns server info and tools capability', async () => {
  const byId = await rpc([
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18' } },
  ]);
  const res = byId.get(1).result;
  assert.equal(res.serverInfo.name, 'gh-readonly');
  assert.ok(res.capabilities.tools);
  assert.equal(res.protocolVersion, '2025-06-18');
});

test('tools/list exposes only read-only gh tools', async () => {
  const byId = await rpc([
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} },
    { jsonrpc: '2.0', id: 2, method: 'tools/list' },
  ]);
  const tools = byId.get(2).result.tools;
  assert.ok(tools.length >= 10);
  for (const t of tools) {
    assert.match(t.name, /^gh_/);
    assert.doesNotMatch(t.name, /create|delete|merge|edit|close|comment/);
  }
});

test('tools/call with bad input yields an isError tool result (not a crash)', async () => {
  const byId = await rpc([
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} },
    { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'gh_issue_view', arguments: {} } },
  ]);
  const res = byId.get(2).result;
  assert.equal(res.isError, true);
  assert.match(res.content[0].text, /required/);
});

test('unknown method returns a JSON-RPC method-not-found error', async () => {
  const byId = await rpc([
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} },
    { jsonrpc: '2.0', id: 2, method: 'nope/whatever' },
  ]);
  assert.equal(byId.get(2).error.code, -32601);
});
