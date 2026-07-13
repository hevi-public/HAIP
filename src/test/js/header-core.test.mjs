import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldScrollToTop } from "../../main/resources/static/header-core.mjs";

/*
 * The header is a flex bar of three interactive zones (brand link, nav links, theme buttons)
 * separated by empty chrome. A click scrolls to top ONLY when it lands on the bare chrome — every
 * real control keeps its own behaviour. pathTags is the walk from the clicked node up to the header.
 */

test("empty header chrome (no control in the path) scrolls to top", () => {
  // e.g. the gap between the nav and the theme toggle — path is just the header itself.
  assert.equal(shouldScrollToTop(["header"]), true);
});

test("a click whose path crosses the brand link does not scroll", () => {
  // span.site-header__wordmark -> a.site-header__brand -> header
  assert.equal(shouldScrollToTop(["span", "a", "header"]), false);
});

test("a click on a nav link does not scroll", () => {
  assert.equal(shouldScrollToTop(["a", "nav", "header"]), false);
});

test("a click on a theme-toggle button (or its inner span) does not scroll", () => {
  assert.equal(shouldScrollToTop(["span", "button", "div", "header"]), false);
  assert.equal(shouldScrollToTop(["button", "div", "header"]), false);
});

test("tag matching is case-insensitive", () => {
  assert.equal(shouldScrollToTop(["A", "HEADER"]), false);
});

test("a non-array path is treated as 'do not scroll'", () => {
  assert.equal(shouldScrollToTop(null), false);
  assert.equal(shouldScrollToTop(undefined), false);
});
