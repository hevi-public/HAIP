/*
 * quote-core — pure helpers for the "Quote" context-menu feature (plan_docs/comment-quotes.md). NO DOM,
 * NO globals. The DOM glue (text selection, the context menu, smart destination, htmx wiring) lives in
 * quote.js; this is the unit-tested decision layer (src/test/js/quote-core.test.mjs).
 */

/**
 * Turn selected text into a markdown blockquote: every non-empty line prefixed with "> ", blank lines as
 * a bare ">". Leading/trailing blank lines are trimmed so the quote is tight; CRLF/CR normalised to LF.
 * Returns "" for blank input.
 */
export function toBlockquote(text) {
  const normalized = (text || "").replace(/\r\n?/g, "\n");
  if (normalized.trim() === "") return "";
  const lines = normalized.split("\n");
  while (lines.length && lines[0].trim() === "") lines.shift(); // drop leading blank lines
  while (lines.length && lines[lines.length - 1].trim() === "") lines.pop(); // and trailing
  return lines.map((line) => (line.trim() === "" ? ">" : "> " + line)).join("\n");
}

/**
 * Append a blockquote of [quoteText] to the composer's [existing] text, separated by a blank line and
 * leaving a trailing blank line so the caret lands ready for the reply. Existing trailing whitespace is
 * collapsed first so quoting twice doesn't pile up blank lines. A blank quote leaves the text unchanged.
 */
export function appendQuote(existing, quoteText) {
  const bq = toBlockquote(quoteText);
  if (bq === "") return existing || "";
  const base = (existing || "").replace(/\s+$/g, "");
  const sep = base === "" ? "" : "\n\n";
  return base + sep + bq + "\n\n";
}

/**
 * Serialise pending quotes to the `quotesJson` wire string the server parses into QuoteSpecs. Entries
 * missing a targetId or text are dropped; an empty result is "" (so the caller sends nothing).
 */
export function serializeQuotes(quotes) {
  const clean = (quotes || [])
    .filter((q) => q && q.targetId && q.text)
    .map((q) => ({ targetId: q.targetId, text: q.text }));
  return clean.length ? JSON.stringify(clean) : "";
}
