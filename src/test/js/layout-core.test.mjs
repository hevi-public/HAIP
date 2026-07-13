import { test } from "node:test";
import assert from "node:assert/strict";
import { isWide, toggleRail, toggleWide } from "../../main/resources/static/layout-core.mjs";

/*
 * The collapsible-sidebars state is two booleans — { left, right } — for whether each rail is
 * collapsed. "Wide" / full-screen is the derived case where both are collapsed. These tests pin the
 * transitions the glue (layout.js) drives off clicks; the glue itself is thin DOM plumbing.
 */

const OPEN = { left: false, right: false };

test("isWide is true only when both rails are collapsed", () => {
  assert.equal(isWide({ left: true, right: true }), true);
  assert.equal(isWide({ left: true, right: false }), false);
  assert.equal(isWide({ left: false, right: true }), false);
  assert.equal(isWide(OPEN), false);
});

test("isWide tolerates a missing/garbage state", () => {
  assert.equal(isWide(null), false);
  assert.equal(isWide(undefined), false);
  assert.equal(isWide({}), false);
});

test("toggleRail flips one side and leaves the other untouched", () => {
  assert.deepEqual(toggleRail(OPEN, "left"), { left: true, right: false });
  assert.deepEqual(toggleRail(OPEN, "right"), { left: false, right: true });
  assert.deepEqual(toggleRail({ left: true, right: false }, "left"), { left: false, right: false });
});

test("toggleRail is its own inverse (idempotent in pairs)", () => {
  assert.deepEqual(toggleRail(toggleRail(OPEN, "left"), "left"), OPEN);
});

test("toggleRail ignores an unknown side and normalises the state", () => {
  assert.deepEqual(toggleRail({ left: true, right: false }, "middle"), { left: true, right: false });
  assert.deepEqual(toggleRail(null, "bogus"), { left: false, right: false });
});

test("toggleWide collapses both when not already wide", () => {
  assert.deepEqual(toggleWide(OPEN), { left: true, right: true });
  assert.deepEqual(toggleWide({ left: true, right: false }), { left: true, right: true });
  assert.deepEqual(toggleWide({ left: false, right: true }), { left: true, right: true });
});

test("toggleWide restores both when already wide", () => {
  assert.deepEqual(toggleWide({ left: true, right: true }), { left: false, right: false });
});

test("toggleWide round-trips from open", () => {
  assert.deepEqual(toggleWide(toggleWide(OPEN)), OPEN);
});
