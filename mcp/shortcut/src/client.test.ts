import { test } from "node:test";
import assert from "node:assert/strict";
import { buildUrl, DEFAULT_BASE_URL, ShortcutApiError } from "./client.js";

test("buildUrl joins base and path", () => {
  const url = buildUrl(DEFAULT_BASE_URL, "/stories/42");
  assert.equal(url.toString(), "https://api.app.shortcut.com/api/v3/stories/42");
});

test("buildUrl tolerates a trailing slash on the base", () => {
  const url = buildUrl("https://api.app.shortcut.com/api/v3/", "/member");
  assert.equal(url.pathname, "/api/v3/member");
});

test("buildUrl appends supplied query params", () => {
  const url = buildUrl(DEFAULT_BASE_URL, "/search/stories", {
    query: "type:bug owner:jane",
    page_size: 25,
  });
  assert.equal(url.searchParams.get("query"), "type:bug owner:jane");
  assert.equal(url.searchParams.get("page_size"), "25");
});

test("buildUrl drops undefined, null, and empty query values", () => {
  const url = buildUrl(DEFAULT_BASE_URL, "/labels", {
    slim: undefined,
    next: null,
    detail: "",
    page_size: 10,
  });
  assert.equal(url.search, "?page_size=10");
});

test("buildUrl serialises boolean query values", () => {
  const url = buildUrl(DEFAULT_BASE_URL, "/epics/7/stories", {
    includes_description: true,
  });
  assert.equal(url.searchParams.get("includes_description"), "true");
});

test("ShortcutApiError carries status and a tailored hint", () => {
  const unauthorized = new ShortcutApiError(401, "Unauthorized", "/member", "");
  assert.equal(unauthorized.status, 401);
  assert.match(unauthorized.hint(), /token/i);

  const rateLimited = new ShortcutApiError(429, "Too Many Requests", "/stories/1", "");
  assert.match(rateLimited.hint(), /rate limit/i);

  const serverError = new ShortcutApiError(503, "Service Unavailable", "/epics", "");
  assert.match(serverError.hint(), /retry/i);
});
