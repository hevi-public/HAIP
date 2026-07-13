import { test } from "node:test";
import assert from "node:assert/strict";
import { matchPassage } from "../../main/resources/static/quote-backlinks-core.mjs";

/*
 * Frontend "Tier 0" for the quote backlinks (plan_docs/comment-quotes.md §6). matchPassage re-finds a
 * quoted snapshot inside a comment's rendered text so quote-backlinks.js can highlight it. Pure, no DOM.
 */

test("matchPassage — exact substring returns its offsets", () => {
  assert.deepEqual(matchPassage("a recursive CTE keeps it fast", "recursive CTE"), { start: 2, end: 15 });
});

test("matchPassage — whitespace-tolerant: a one-line needle matches body text wrapped across a newline", () => {
  const hay = "use a recursive\nCTE to keep it";
  const hit = matchPassage(hay, "recursive CTE");
  assert.ok(hit);
  assert.equal(hay.slice(hit.start, hit.end), "recursive\nCTE");
});

test("matchPassage — collapses runs of whitespace in the needle too", () => {
  assert.deepEqual(matchPassage("the recursive CTE", "recursive   CTE"), { start: 4, end: 17 });
});

test("matchPassage — fromIndex skips earlier matches", () => {
  const hay = "CTE then CTE again";
  const first = matchPassage(hay, "CTE");
  assert.equal(first.start, 0);
  const second = matchPassage(hay, "CTE", first.end);
  assert.equal(second.start, 9);
});

test("matchPassage — regex metacharacters in the needle are matched literally", () => {
  const hay = "cost is O(n) on a cold cache";
  const hit = matchPassage(hay, "O(n)");
  assert.ok(hit);
  assert.equal(hay.slice(hit.start, hit.end), "O(n)");
});

test("matchPassage — returns null when the passage is absent", () => {
  assert.equal(matchPassage("nothing here", "missing"), null);
});

test("matchPassage — a blank needle is null", () => {
  assert.equal(matchPassage("abc", "   "), null);
  assert.equal(matchPassage("abc", ""), null);
});
