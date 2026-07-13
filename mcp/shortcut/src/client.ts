/**
 * Thin read-only client for the Shortcut REST API (v3).
 *
 * Auth: a personal API token passed in the `Shortcut-Token` header.
 * Base URL: https://api.app.shortcut.com/api/v3
 * Docs:    https://developer.shortcut.com/api/rest/v3
 */

export const DEFAULT_BASE_URL = "https://api.app.shortcut.com/api/v3";

export type QueryValue = string | number | boolean | undefined | null;

/** An error carrying the HTTP status and (best-effort) body from the Shortcut API. */
export class ShortcutApiError extends Error {
  constructor(
    readonly status: number,
    readonly statusText: string,
    readonly path: string,
    readonly body: string,
  ) {
    super(`Shortcut API ${status} ${statusText} for ${path}`);
    this.name = "ShortcutApiError";
  }

  /** A human-friendly hint for the common failure modes. */
  hint(): string {
    switch (this.status) {
      case 400:
        return "Bad request — check the query/parameters (e.g. an unsupported search operator).";
      case 401:
        return "Unauthorized — the SHORTCUT_API_TOKEN is missing, invalid, or revoked.";
      case 403:
        return "Forbidden — the token lacks access to this resource.";
      case 404:
        return "Not found — no such id, or it is outside this token's workspace.";
      case 429:
        return "Rate limited — Shortcut throttles to ~200 requests/minute; back off and retry.";
      default:
        return this.status >= 500
          ? "Shortcut server error — transient; retry shortly."
          : "Request failed.";
    }
  }
}

/**
 * Build a fully-qualified URL from a normalised base, a path, and a query map.
 * Empty/undefined/null query values are dropped so optional tool args don't
 * leak `?foo=` noise into the request. Pure — extracted for unit testing.
 */
export function buildUrl(
  baseUrl: string,
  path: string,
  query?: Record<string, QueryValue>,
): URL {
  const url = new URL(baseUrl.replace(/\/+$/, "") + path);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    }
  }
  return url;
}

export class ShortcutClient {
  private readonly baseUrl: string;

  constructor(
    private readonly token: string,
    baseUrl: string = DEFAULT_BASE_URL,
  ) {
    if (!token) throw new Error("ShortcutClient requires a non-empty API token.");
    // Normalise away any trailing slash so path joins are predictable.
    this.baseUrl = baseUrl.replace(/\/+$/, "");
  }

  /** Issue a GET against `path` (which must start with "/"), returning parsed JSON. */
  async get(path: string, query?: Record<string, QueryValue>): Promise<unknown> {
    const url = buildUrl(this.baseUrl, path, query);

    const res = await fetch(url, {
      method: "GET",
      headers: {
        "Shortcut-Token": this.token,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
    });

    if (!res.ok) {
      const body = await res.text().catch(() => "");
      throw new ShortcutApiError(res.status, res.statusText, url.pathname, body);
    }

    return res.json();
  }
}
