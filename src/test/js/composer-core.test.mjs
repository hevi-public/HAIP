import { test } from "node:test";
import assert from "node:assert/strict";
import {
  reduceMode,
  submitLabel,
  toggleLabel,
  toggleCmd,
  notePath,
} from "../../main/resources/static/composer-core.mjs";

/*
 * The frontend "Tier 0" for the composer's note/ask toggle (Step 2). Pure mode model, no DOM: the
 * composer posts to /generate (summon) in "ask" mode or /note (silent owner comment) in "note" mode,
 * and the one footer button alternates between them. DOM glue (app.js) reads the form's current mode
 * and applies these labels/commands.
 */

test("reduceMode — /note enters note mode, /ask returns to ask", () => {
  assert.equal(reduceMode("ask", "note"), "note");
  assert.equal(reduceMode("note", "ask"), "ask");
  // already there → unchanged
  assert.equal(reduceMode("note", "note"), "note");
  assert.equal(reduceMode("ask", "ask"), "ask");
});

test("reduceMode — the scope commands only apply to a real summon, so they leave note mode", () => {
  assert.equal(reduceMode("note", "branch"), "ask");
  assert.equal(reduceMode("note", "topic"), "ask");
  assert.equal(reduceMode("ask", "branch"), "ask");
});

test("reduceMode — an unknown command is a no-op", () => {
  assert.equal(reduceMode("note", "mention"), "note");
  assert.equal(reduceMode("ask", ""), "ask");
});

test("submitLabel — the button says what pressing it does", () => {
  assert.equal(submitLabel("ask"), "Ask ▸");
  assert.equal(submitLabel("note"), "Note ▸");
});

test("toggleLabel / toggleCmd — the footer button offers the OTHER mode", () => {
  // In ask mode the toggle invites you into note mode, and vice-versa.
  assert.equal(toggleLabel("ask"), "/note");
  assert.equal(toggleCmd("ask"), "note");
  assert.equal(toggleLabel("note"), "/ask");
  assert.equal(toggleCmd("note"), "ask");
});

test("notePath — rewrites the /generate request URL to /note (the actual summon-or-not switch)", () => {
  // This is the load-bearing fix: note mode must REDIRECT the request, not just relabel the button.
  // htmx caches the form's path, so we rewrite it at htmx:configRequest time using this.
  assert.equal(notePath("/threads/abc/generate"), "/threads/abc/note");
  assert.equal(notePath("/threads/abc/note"), "/threads/abc/note", "idempotent — already a note");
  // only the trailing /generate segment flips, never a substring elsewhere in the path
  assert.equal(notePath("/x/generate-stuff/generate"), "/x/generate-stuff/note");
  // a query string survives the rewrite
  assert.equal(notePath("/threads/abc/generate?foo=1"), "/threads/abc/note?foo=1");
});

test("a full alternation: ask → /note → note → /ask → ask", () => {
  let mode = "ask";
  assert.equal(toggleLabel(mode), "/note", "starts offering /note");
  mode = reduceMode(mode, toggleCmd(mode)); // click the toggle
  assert.equal(mode, "note");
  assert.equal(submitLabel(mode), "Note ▸");
  assert.equal(toggleLabel(mode), "/ask", "now offers /ask back");
  mode = reduceMode(mode, toggleCmd(mode)); // click it again
  assert.equal(mode, "ask");
  assert.equal(submitLabel(mode), "Ask ▸");
});
