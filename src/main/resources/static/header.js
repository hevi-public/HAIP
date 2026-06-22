/*
 * header.js — DOM glue for "click the header to scroll to top" (see plan_docs/sticky-header.md).
 *
 * Pure progressive enhancement: a click on the bare header chrome scrolls the page to the top; the
 * brand link, nav links and theme buttons keep their own behaviour. The decision (interactive control
 * in the click path? then don't scroll) lives in header-core.mjs and is unit-tested — this file just
 * collects the path and performs the scroll. Activates wherever [data-scroll-top] exists, no-ops else.
 */
import { shouldScrollToTop } from "./header-core.mjs";

const HEADER = "[data-scroll-top]";

document.addEventListener("click", function (e) {
  const header = e.target.closest(HEADER);
  if (!header) return;

  // Walk from the clicked node up to (and including) the header, collecting tag names for the core.
  const tags = [];
  for (let el = e.target; el; el = el.parentElement) {
    tags.push(el.tagName.toLowerCase());
    if (el === header) break;
  }
  if (!shouldScrollToTop(tags)) return;

  const smooth = !matchMedia("(prefers-reduced-motion: reduce)").matches;
  window.scrollTo({ top: 0, behavior: smooth ? "smooth" : "auto" });
});
