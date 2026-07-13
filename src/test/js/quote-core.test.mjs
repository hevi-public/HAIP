import { test } from "node:test";
import assert from "node:assert/strict";
import { toBlockquote, appendQuote, serializeQuotes } from "../../main/resources/static/quote-core.mjs";

/*
 * The frontend "Tier 0" for the Quote context menu (plan_docs/comment-quotes.md). Pure helpers, no DOM:
 * build a markdown blockquote from a selection, append it to the composer text, and serialise the
 * pending quotes to the quotesJson wire string. DOM glue (quote.js) wires these to the selection,
 * the menu, and htmx.
 */

test("toBlockquote — a single line gets a '> ' prefix", () => {
  assert.equal(toBlockquote("hello world"), "> hello world");
});

test("toBlockquote — every line is prefixed; blank lines become a bare '>'", () => {
  assert.equal(toBlockquote("a\n\nb"), "> a\n>\n> b");
});

test("toBlockquote — leading/trailing blank lines trimmed, CRLF normalised", () => {
  assert.equal(toBlockquote("\r\nfoo\r\nbar\r\n\r\n"), "> foo\n> bar");
});

test("toBlockquote — blank input yields empty string", () => {
  assert.equal(toBlockquote("   \n  "), "");
  assert.equal(toBlockquote(""), "");
  assert.equal(toBlockquote(null), "");
});

test("appendQuote — into an empty composer: just the quote + a trailing blank line", () => {
  assert.equal(appendQuote("", "hi"), "> hi\n\n");
});

test("appendQuote — separates from existing text by a blank line", () => {
  assert.equal(appendQuote("draft", "hi"), "draft\n\n> hi\n\n");
});

test("appendQuote — collapses existing trailing whitespace so quoting twice doesn't pile up", () => {
  const once = appendQuote("", "a"); // "> a\n\n"
  assert.equal(appendQuote(once, "b"), "> a\n\n> b\n\n");
});

test("appendQuote — a blank quote leaves the text unchanged", () => {
  assert.equal(appendQuote("draft", "  "), "draft");
});

test("serializeQuotes — round-trips targetId + text as a JSON array", () => {
  assert.equal(
    serializeQuotes([{ targetId: "c1", text: "hello" }]),
    JSON.stringify([{ targetId: "c1", text: "hello" }]),
  );
});

test("serializeQuotes — drops entries missing targetId or text", () => {
  assert.equal(serializeQuotes([{ targetId: "", text: "x" }, { targetId: "c2", text: "" }]), "");
});

test("serializeQuotes — empty / absent yields an empty string (send nothing)", () => {
  assert.equal(serializeQuotes([]), "");
  assert.equal(serializeQuotes(null), "");
});

test("serializeQuotes — keeps only targetId + text, dropping any extra fields", () => {
  assert.equal(
    serializeQuotes([{ targetId: "c1", text: "hi", junk: 1 }]),
    JSON.stringify([{ targetId: "c1", text: "hi" }]),
  );
});
