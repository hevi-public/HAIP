/*
 * quote-backlinks-core — pure helper for locating a quoted passage inside a comment body
 * (plan_docs/comment-quotes.md, backward slice). NO DOM. Unit-tested in
 * src/test/js/quote-backlinks-core.test.mjs; the DOM glue (text-node walking, <mark> wrapping, the hover
 * cone) lives in quote-backlinks.js.
 */

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Whitespace-tolerant search for [needle] in [haystack], starting at [fromIndex]. Any run of whitespace
 * in the needle matches any run of whitespace in the haystack, so a passage that was selected as one line
 * still matches body text that the renderer wrapped across lines (the snapshot and the rendered text often
 * differ only in whitespace). Returns `{ start, end }` offsets into the ORIGINAL [haystack] — so the
 * caller can map them straight onto the text node — or `null` if not found.
 */
export function matchPassage(haystack, needle, fromIndex = 0) {
  const collapsed = (needle || "").replace(/\s+/g, " ").trim();
  if (!collapsed) return null;
  const re = new RegExp(collapsed.split(" ").map(escapeRegExp).join("\\s+"));
  const m = re.exec(haystack.slice(fromIndex));
  return m ? { start: fromIndex + m.index, end: fromIndex + m.index + m[0].length } : null;
}
