// Read-only `gh` (GitHub CLI) tool definitions for the MCP server.
//
// Every tool here builds a fixed `gh` argv array from validated input. The
// server never runs a shell, never interpolates strings into a command line,
// and never exposes a write/mutate subcommand. `assertReadOnly` is a
// defence-in-depth guard re-checked on every constructed argv.
//
// Pure ESM, zero runtime dependencies (matches the repo convention).

// ---------------------------------------------------------------------------
// Input validation helpers (throw a plain Error; the server maps it to a
// JSON-RPC tool error result).
// ---------------------------------------------------------------------------

function fail(msg) {
  throw new Error(msg);
}

function str(input, key, { required = false } = {}) {
  const v = input?.[key];
  if (v === undefined || v === null) {
    if (required) fail(`'${key}' is required`);
    return undefined;
  }
  if (typeof v !== 'string') fail(`'${key}' must be a string`);
  if (v.length === 0) fail(`'${key}' must not be empty`);
  // gh treats a leading '-' as a flag; never let user input become one.
  if (v.startsWith('-')) fail(`'${key}' must not start with '-'`);
  return v;
}

function strArray(input, key) {
  const v = input?.[key];
  if (v === undefined || v === null) return [];
  if (!Array.isArray(v)) fail(`'${key}' must be an array of strings`);
  return v.map((item, i) => {
    if (typeof item !== 'string' || item.length === 0) {
      fail(`'${key}[${i}]' must be a non-empty string`);
    }
    if (item.startsWith('-')) fail(`'${key}[${i}]' must not start with '-'`);
    return item;
  });
}

function int(input, key, { min = 1, max = 1000 } = {}) {
  const v = input?.[key];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== 'number' || !Number.isInteger(v)) fail(`'${key}' must be an integer`);
  if (v < min || v > max) fail(`'${key}' must be between ${min} and ${max}`);
  return v;
}

function enumStr(input, key, allowed, { required = false } = {}) {
  const v = str(input, key, { required });
  if (v === undefined) return undefined;
  if (!allowed.includes(v)) fail(`'${key}' must be one of: ${allowed.join(', ')}`);
  return v;
}

// A `--repo OWNER/REPO` pair, only when provided. gh falls back to the cwd repo.
function repoFlag(input) {
  const repo = str(input, 'repo');
  if (!repo) return [];
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
    fail(`'repo' must look like 'OWNER/REPO'`);
  }
  return ['--repo', repo];
}

// ---------------------------------------------------------------------------
// Tool definitions. Each: name, description, inputSchema (JSON Schema for
// tools/list), and build(input) -> argv array passed to `gh`.
// ---------------------------------------------------------------------------

export const TOOLS = [
  {
    name: 'gh_repo_view',
    description: 'View metadata for a GitHub repository (description, topics, default branch, counts). Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        repo: { type: 'string', description: "Target repo as 'OWNER/REPO'. Omit to use the current directory's repo." },
      },
    },
    build(input) {
      const repo = str(input, 'repo');
      if (repo && !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
        fail(`'repo' must look like 'OWNER/REPO'`);
      }
      return ['repo', 'view', ...(repo ? [repo] : [])];
    },
  },

  {
    name: 'gh_issue_list',
    description: 'List issues in a repository, with optional filters. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        state: { type: 'string', enum: ['open', 'closed', 'all'], description: 'Issue state filter (default: open).' },
        limit: { type: 'integer', description: 'Max results (1-200, default 30).' },
        labels: { type: 'array', items: { type: 'string' }, description: 'Filter by label(s).' },
        assignee: { type: 'string', description: 'Filter by assignee login.' },
        author: { type: 'string', description: 'Filter by author login.' },
        search: { type: 'string', description: 'Search issues with GitHub search syntax.' },
      },
    },
    build(input) {
      const argv = ['issue', 'list', ...repoFlag(input)];
      const state = enumStr(input, 'state', ['open', 'closed', 'all']);
      if (state) argv.push('--state', state);
      const limit = int(input, 'limit', { min: 1, max: 200 });
      if (limit) argv.push('--limit', String(limit));
      for (const label of strArray(input, 'labels')) argv.push('--label', label);
      const assignee = str(input, 'assignee');
      if (assignee) argv.push('--assignee', assignee);
      const author = str(input, 'author');
      if (author) argv.push('--author', author);
      const search = str(input, 'search');
      if (search) argv.push('--search', search);
      return argv;
    },
  },

  {
    name: 'gh_issue_view',
    description: 'View a single issue, optionally with its comments. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        issue: { type: 'string', description: 'Issue number or URL.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        comments: { type: 'boolean', description: 'Include comments.' },
      },
      required: ['issue'],
    },
    build(input) {
      const issue = str(input, 'issue', { required: true });
      const argv = ['issue', 'view', issue, ...repoFlag(input)];
      if (input?.comments === true) argv.push('--comments');
      return argv;
    },
  },

  {
    name: 'gh_pr_list',
    description: 'List pull requests in a repository, with optional filters. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        state: { type: 'string', enum: ['open', 'closed', 'merged', 'all'], description: 'PR state filter (default: open).' },
        limit: { type: 'integer', description: 'Max results (1-200, default 30).' },
        labels: { type: 'array', items: { type: 'string' }, description: 'Filter by label(s).' },
        author: { type: 'string', description: 'Filter by author login.' },
        base: { type: 'string', description: 'Filter by base branch.' },
        search: { type: 'string', description: 'Search PRs with GitHub search syntax.' },
      },
    },
    build(input) {
      const argv = ['pr', 'list', ...repoFlag(input)];
      const state = enumStr(input, 'state', ['open', 'closed', 'merged', 'all']);
      if (state) argv.push('--state', state);
      const limit = int(input, 'limit', { min: 1, max: 200 });
      if (limit) argv.push('--limit', String(limit));
      for (const label of strArray(input, 'labels')) argv.push('--label', label);
      const author = str(input, 'author');
      if (author) argv.push('--author', author);
      const base = str(input, 'base');
      if (base) argv.push('--base', base);
      const search = str(input, 'search');
      if (search) argv.push('--search', search);
      return argv;
    },
  },

  {
    name: 'gh_pr_view',
    description: 'View a single pull request, optionally with its comments. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        pr: { type: 'string', description: 'PR number, URL, or branch. Omit to use the current branch.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        comments: { type: 'boolean', description: 'Include comments.' },
      },
    },
    build(input) {
      const pr = str(input, 'pr');
      const argv = ['pr', 'view', ...(pr ? [pr] : []), ...repoFlag(input)];
      if (input?.comments === true) argv.push('--comments');
      return argv;
    },
  },

  {
    name: 'gh_pr_diff',
    description: 'Show the diff of a pull request. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        pr: { type: 'string', description: 'PR number, URL, or branch. Omit to use the current branch.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
      },
    },
    build(input) {
      const pr = str(input, 'pr');
      return ['pr', 'diff', ...(pr ? [pr] : []), ...repoFlag(input)];
    },
  },

  {
    name: 'gh_pr_checks',
    description: 'Show the CI check status for a pull request. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        pr: { type: 'string', description: 'PR number, URL, or branch. Omit to use the current branch.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
      },
    },
    build(input) {
      const pr = str(input, 'pr');
      return ['pr', 'checks', ...(pr ? [pr] : []), ...repoFlag(input)];
    },
  },

  {
    name: 'gh_release_list',
    description: 'List releases in a repository. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        limit: { type: 'integer', description: 'Max results (1-200, default 30).' },
      },
    },
    build(input) {
      const argv = ['release', 'list', ...repoFlag(input)];
      const limit = int(input, 'limit', { min: 1, max: 200 });
      if (limit) argv.push('--limit', String(limit));
      return argv;
    },
  },

  {
    name: 'gh_release_view',
    description: 'View a single release (defaults to the latest). Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        tag: { type: 'string', description: 'Release tag. Omit for the latest release.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
      },
    },
    build(input) {
      const tag = str(input, 'tag');
      return ['release', 'view', ...(tag ? [tag] : []), ...repoFlag(input)];
    },
  },

  {
    name: 'gh_run_list',
    description: 'List GitHub Actions workflow runs. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        limit: { type: 'integer', description: 'Max results (1-200, default 20).' },
        branch: { type: 'string', description: 'Filter by branch.' },
        workflow: { type: 'string', description: 'Filter by workflow name or file.' },
        status: {
          type: 'string',
          enum: ['queued', 'in_progress', 'completed', 'success', 'failure', 'cancelled', 'skipped'],
          description: 'Filter by run status/conclusion.',
        },
      },
    },
    build(input) {
      const argv = ['run', 'list', ...repoFlag(input)];
      const limit = int(input, 'limit', { min: 1, max: 200 });
      if (limit) argv.push('--limit', String(limit));
      const branch = str(input, 'branch');
      if (branch) argv.push('--branch', branch);
      const workflow = str(input, 'workflow');
      if (workflow) argv.push('--workflow', workflow);
      const status = enumStr(input, 'status', [
        'queued', 'in_progress', 'completed', 'success', 'failure', 'cancelled', 'skipped',
      ]);
      if (status) argv.push('--status', status);
      return argv;
    },
  },

  {
    name: 'gh_run_view',
    description: 'View a single GitHub Actions run, optionally with its logs. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        run: { type: 'string', description: 'Run ID.' },
        repo: { type: 'string', description: "Target repo 'OWNER/REPO'. Omit for the current repo." },
        log: { type: 'boolean', description: 'Include the full run log.' },
      },
      required: ['run'],
    },
    build(input) {
      const run = str(input, 'run', { required: true });
      const argv = ['run', 'view', run, ...repoFlag(input)];
      if (input?.log === true) argv.push('--log');
      return argv;
    },
  },

  {
    name: 'gh_search',
    description: 'Search GitHub for repositories, issues, pull requests, code, or commits. Read-only.',
    inputSchema: {
      type: 'object',
      properties: {
        type: { type: 'string', enum: ['repos', 'issues', 'prs', 'code', 'commits'], description: 'What to search.' },
        query: { type: 'string', description: 'Search query (GitHub search syntax).' },
        limit: { type: 'integer', description: 'Max results (1-100, default 30).' },
      },
      required: ['type', 'query'],
    },
    build(input) {
      const type = enumStr(input, 'type', ['repos', 'issues', 'prs', 'code', 'commits'], { required: true });
      const query = str(input, 'query', { required: true });
      const argv = ['search', type, query];
      const limit = int(input, 'limit', { min: 1, max: 100 });
      if (limit) argv.push('--limit', String(limit));
      return argv;
    },
  },

  {
    name: 'gh_api_get',
    description:
      'Make a GET request to the GitHub REST API. Hard-restricted to GET (read-only); ' +
      'the GraphQL endpoint and any mutating method are rejected. Params become query-string fields.',
    inputSchema: {
      type: 'object',
      properties: {
        endpoint: {
          type: 'string',
          description: "REST endpoint, e.g. 'repos/OWNER/REPO/commits' or 'user'. Must not be 'graphql'.",
        },
        params: {
          type: 'object',
          description: 'Optional query-string params, as a flat string/number map (e.g. {per_page: 50}).',
          additionalProperties: { type: ['string', 'number', 'boolean'] },
        },
        hostname: { type: 'string', description: 'GitHub Enterprise hostname (optional).' },
      },
      required: ['endpoint'],
    },
    build(input) {
      const endpoint = str(input, 'endpoint', { required: true });
      // gh routes `graphql` (and any path under it) over POST; block it outright.
      if (/^graphql(\/|$)/i.test(endpoint)) fail("'gh api graphql' is not read-only and is not allowed");
      // Force GET explicitly so this can never be flipped to a mutation.
      const argv = ['api', '--method', 'GET', endpoint];
      const hostname = str(input, 'hostname');
      if (hostname) argv.push('--hostname', hostname);
      const params = input?.params;
      if (params !== undefined && params !== null) {
        if (typeof params !== 'object' || Array.isArray(params)) fail("'params' must be an object");
        for (const [k, v] of Object.entries(params)) {
          if (!/^[A-Za-z0-9_.\[\]]+$/.test(k)) fail(`invalid param key '${k}'`);
          if (typeof v !== 'string' && typeof v !== 'number' && typeof v !== 'boolean') {
            fail(`param '${k}' must be a string, number, or boolean`);
          }
          // `-f` adds a field; under GET gh appends it to the query string.
          argv.push('-f', `${k}=${v}`);
        }
      }
      return argv;
    },
  },
];

export const TOOLS_BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));

// ---------------------------------------------------------------------------
// Defence-in-depth read-only guard. Every constructed argv is re-checked here
// before it is ever handed to `gh`, independent of the per-tool builders.
// ---------------------------------------------------------------------------

// Top-level `gh` commands this server is ever allowed to invoke.
const ALLOWED_COMMANDS = new Set(['repo', 'issue', 'pr', 'release', 'run', 'search', 'api']);

// Verbs that mutate state — must never appear anywhere in a constructed argv.
const BANNED_TOKENS = new Set([
  'create', 'delete', 'edit', 'close', 'reopen', 'merge', 'comment', 'review',
  'rename', 'transfer', 'lock', 'unlock', 'pin', 'unpin', 'develop', 'ready',
  'checkout', 'rerun', 'cancel', 'sync', 'fork', 'clone', 'set-default',
  'archive', 'unarchive', 'add', 'remove', 'update', 'upload', 'download',
  'restore', 'delete-asset', 'add-collaborator', 'disable', 'enable',
]);

// Per-command allowed subcommands (the only read verbs we expose).
const ALLOWED_SUBCOMMANDS = {
  repo: new Set(['view']),
  issue: new Set(['list', 'view']),
  pr: new Set(['list', 'view', 'diff', 'checks']),
  release: new Set(['list', 'view']),
  run: new Set(['list', 'view']),
  // `search <type>` — the second token is the search type, validated separately.
  search: new Set(['repos', 'issues', 'prs', 'code', 'commits']),
  api: null, // validated by the dedicated branch below
};

export function assertReadOnly(argv) {
  if (!Array.isArray(argv) || argv.length === 0) {
    throw new Error('internal: empty gh argv');
  }
  const [command, sub] = argv;
  if (!ALLOWED_COMMANDS.has(command)) {
    throw new Error(`internal: command '${command}' is not allowed`);
  }

  if (command === 'api') {
    // Must be an explicit GET, and must not target graphql.
    const mi = argv.indexOf('--method');
    if (mi === -1 || (argv[mi + 1] || '').toUpperCase() !== 'GET') {
      throw new Error('internal: gh api must be an explicit GET');
    }
    for (let i = 0; i < argv.length; i++) {
      const tok = argv[i];
      // Reject any second method flag or any non-GET method.
      if ((tok === '--method' || tok === '-X') && (argv[i + 1] || '').toUpperCase() !== 'GET') {
        throw new Error('internal: gh api method must be GET');
      }
      // `-F`/`--field`/`--input` imply a request body (POST); only `-f` query fields are allowed.
      if (tok === '-F' || tok === '--field' || tok === '--input' || tok === '--raw-field') {
        throw new Error('internal: gh api request-body fields are not allowed');
      }
      if (/^graphql(\/|$)/i.test(tok)) {
        throw new Error('internal: gh api graphql is not allowed');
      }
    }
    return argv;
  }

  const allowedSubs = ALLOWED_SUBCOMMANDS[command];
  if (!allowedSubs || !allowedSubs.has(sub)) {
    throw new Error(`internal: '${command} ${sub}' is not an allowed read command`);
  }

  // No mutating verb may appear anywhere (skips api, handled above).
  for (const tok of argv) {
    if (BANNED_TOKENS.has(tok)) {
      throw new Error(`internal: token '${tok}' is not read-only`);
    }
  }
  return argv;
}

// Build + guard in one step. Returns the safe argv for `gh`.
export function buildGhArgs(toolName, input) {
  const tool = TOOLS_BY_NAME.get(toolName);
  if (!tool) throw new Error(`unknown tool '${toolName}'`);
  const argv = tool.build(input ?? {});
  return assertReadOnly(argv);
}
