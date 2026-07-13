/**
 * Read-only MCP tools over the Shortcut REST API.
 *
 * Every tool here is a GET — nothing mutates Shortcut state. Each is registered
 * with `readOnlyHint: true` so MCP clients can surface that to the user/model.
 */

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { ShortcutApiError, type ShortcutClient } from "./client.js";

type ToolResult = {
  content: { type: "text"; text: string }[];
  isError?: boolean;
};

/** Pretty-print any Shortcut payload as the tool's text result. */
function ok(data: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
}

/** Turn a thrown error into a readable, non-throwing tool result. */
function fail(err: unknown): ToolResult {
  if (err instanceof ShortcutApiError) {
    const detail = err.body ? `\n\nResponse body:\n${err.body}` : "";
    return {
      content: [{ type: "text", text: `${err.message}\n${err.hint()}${detail}` }],
      isError: true,
    };
  }
  const message = err instanceof Error ? err.message : String(err);
  return { content: [{ type: "text", text: `Unexpected error: ${message}` }], isError: true };
}

/**
 * Register a read-only tool. The handler does the Shortcut call and returns the
 * raw payload; we wrap success/error formatting in one place.
 */
function registerRead<Shape extends z.ZodRawShape>(
  server: McpServer,
  name: string,
  config: { title: string; description: string; inputSchema: Shape },
  handler: (args: z.objectOutputType<Shape, z.ZodTypeAny>) => Promise<unknown>,
): void {
  // The SDK infers the callback's arg type from `inputSchema` as a mapped
  // type that does not structurally unify with our generic `handler` param,
  // so we bridge with a locally-typed callback and cast at the boundary only.
  const callback = async (args: z.objectOutputType<Shape, z.ZodTypeAny>) => {
    try {
      return ok(await handler(args));
    } catch (err) {
      return fail(err);
    }
  };

  server.registerTool(
    name,
    {
      title: config.title,
      description: config.description,
      inputSchema: config.inputSchema,
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    callback as Parameters<typeof server.registerTool>[2],
  );
}

const publicId = (label: string) =>
  z.coerce.number().int().positive().describe(`${label} public id (the numeric id).`);

export function registerShortcutTools(server: McpServer, client: ShortcutClient): void {
  // ---- Identity -----------------------------------------------------------
  registerRead(
    server,
    "get_current_member",
    {
      title: "Get current member",
      description:
        "Return the member (user) that owns the configured API token, including their id, name, mention name, role, and workspace. Use this to discover 'who am I' before filtering by owner.",
      inputSchema: {},
    },
    () => client.get("/member"),
  );

  registerRead(
    server,
    "list_members",
    {
      title: "List members",
      description: "List all members (users) in the workspace.",
      inputSchema: {},
    },
    () => client.get("/members"),
  );

  registerRead(
    server,
    "get_member",
    {
      title: "Get member",
      description: "Get a single member by their UUID member id.",
      inputSchema: { member_id: z.string().describe("Member UUID (e.g. '12a3...').") },
    },
    ({ member_id }) => client.get(`/members/${encodeURIComponent(member_id)}`),
  );

  // ---- Stories ------------------------------------------------------------
  registerRead(
    server,
    "search_stories",
    {
      title: "Search stories",
      description:
        "Search stories using Shortcut's search syntax. Combine free text with operators such as " +
        "`state:`, `owner:` (mention name), `team:`, `epic:`, `iteration:`, `label:`, `type:` " +
        "(feature|bug|chore), `is:done`, `is:started`, `has:owner`, and `created:`/`updated:` date " +
        "ranges. Example: `owner:jane state:\"In Progress\" type:bug`. Results are paginated; when the " +
        "response includes a `next` token, pass it back as `next` to fetch the following page.",
      inputSchema: {
        query: z.string().min(1).describe("Shortcut search query string."),
        page_size: z.coerce
          .number()
          .int()
          .min(1)
          .max(25)
          .optional()
          .describe("Results per page (1-25, default 25)."),
        detail: z
          .enum(["full", "slim"])
          .optional()
          .describe("`slim` returns lighter story objects; defaults to full."),
        next: z
          .string()
          .optional()
          .describe("Pagination token from a previous response's `next` field."),
      },
    },
    ({ query, page_size, detail, next }) =>
      client.get("/search/stories", { query, page_size, detail, next }),
  );

  registerRead(
    server,
    "get_story",
    {
      title: "Get story",
      description:
        "Get the full detail of a single story by its public id, including description, owners, " +
        "workflow state, epic/iteration, labels, tasks, and comments.",
      inputSchema: { story_id: publicId("Story") },
    },
    ({ story_id }) => client.get(`/stories/${story_id}`),
  );

  // ---- Epics --------------------------------------------------------------
  registerRead(
    server,
    "list_epics",
    {
      title: "List epics",
      description: "List all epics in the workspace.",
      inputSchema: {},
    },
    () => client.get("/epics"),
  );

  registerRead(
    server,
    "get_epic",
    {
      title: "Get epic",
      description: "Get a single epic by its public id, including its stats and associated stories metadata.",
      inputSchema: { epic_id: publicId("Epic") },
    },
    ({ epic_id }) => client.get(`/epics/${epic_id}`),
  );

  registerRead(
    server,
    "list_epic_stories",
    {
      title: "List stories in an epic",
      description: "List the stories belonging to a given epic.",
      inputSchema: {
        epic_id: publicId("Epic"),
        includes_description: z
          .boolean()
          .optional()
          .describe("Include each story's full description (heavier payload)."),
      },
    },
    ({ epic_id, includes_description }) =>
      client.get(`/epics/${epic_id}/stories`, { includes_description }),
  );

  // ---- Iterations ---------------------------------------------------------
  registerRead(
    server,
    "list_iterations",
    {
      title: "List iterations",
      description: "List all iterations (sprints) in the workspace.",
      inputSchema: {},
    },
    () => client.get("/iterations"),
  );

  registerRead(
    server,
    "get_iteration",
    {
      title: "Get iteration",
      description: "Get a single iteration by its public id, including its stats and date range.",
      inputSchema: { iteration_id: publicId("Iteration") },
    },
    ({ iteration_id }) => client.get(`/iterations/${iteration_id}`),
  );

  registerRead(
    server,
    "list_iteration_stories",
    {
      title: "List stories in an iteration",
      description: "List the stories assigned to a given iteration.",
      inputSchema: {
        iteration_id: publicId("Iteration"),
        includes_description: z
          .boolean()
          .optional()
          .describe("Include each story's full description (heavier payload)."),
      },
    },
    ({ iteration_id, includes_description }) =>
      client.get(`/iterations/${iteration_id}/stories`, { includes_description }),
  );

  // ---- Workflows, teams, objectives, labels, projects ---------------------
  registerRead(
    server,
    "list_workflows",
    {
      title: "List workflows",
      description:
        "List all workflows and their states. Use this to map human state names (e.g. 'In Progress') " +
        "to the numeric state ids that stories reference.",
      inputSchema: {},
    },
    () => client.get("/workflows"),
  );

  registerRead(
    server,
    "list_groups",
    {
      title: "List groups (teams)",
      description: "List all groups (Teams) in the workspace.",
      inputSchema: {},
    },
    () => client.get("/groups"),
  );

  registerRead(
    server,
    "list_objectives",
    {
      title: "List objectives",
      description: "List all objectives (formerly Milestones) in the workspace.",
      inputSchema: {},
    },
    () => client.get("/objectives"),
  );

  registerRead(
    server,
    "list_labels",
    {
      title: "List labels",
      description: "List all labels in the workspace.",
      inputSchema: {
        slim: z
          .boolean()
          .optional()
          .describe("Return slim label objects without per-label stats."),
      },
    },
    ({ slim }) => client.get("/labels", { slim }),
  );

  registerRead(
    server,
    "list_projects",
    {
      title: "List projects",
      description:
        "List all projects in the workspace. (Projects are a legacy grouping; newer workspaces use " +
        "Teams/groups instead, but projects remain readable via the API.)",
      inputSchema: {},
    },
    () => client.get("/projects"),
  );
}
