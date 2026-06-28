/*
 * quote.js — a floating "Quote" toolbar over a text selection in a comment (plan_docs/comment-quotes.md,
 * forward slice).
 *
 * Select text in a comment (a normal left-drag / double-click) and a small "❝ Quote" toolbar fades in
 * above the selection — leaving the browser's NATIVE right-click menu (Copy / Search / Inspect) fully
 * intact. Choosing Quote inserts the selection as a markdown blockquote into a composer (the one you're
 * already typing in, else a fresh inline reply opened under the quoted comment) and records a quote edge
 * (this reply → the source) by attaching `quotesJson` to the composer's POST at htmx:configRequest time.
 * The forward "↗ author" anchor then renders server-side.
 *
 * Pure logic (blockquote building, serialisation) lives in quote-core.mjs (unit-tested); this is DOM glue
 * and is intentionally untested by the HTTP suite (same split as nav.js). Progressive enhancement: with
 * JS off there's no toolbar, but a hand-typed `> ` blockquote still posts — just without the link.
 */
import { appendQuote, serializeQuotes } from "./quote-core.mjs";

// Pending quotes per composer form, drained into quotesJson at submit time and cleared after it settles.
const pendingByForm = new WeakMap();
// The composer textarea the owner most recently focused — the "active" destination when one is open.
let lastComposerText = null;
let toolbarEl = null;
// { text, source } snapshot of the selection the toolbar is currently shown for — a fallback in case the
// live selection is lost when the button takes the click.
let captured = null;

/** The current selection as { text, source-comment } when it's a non-empty range inside a comment body,
 *  else null. Scoped to `.reply .body`: the OP body (.thread__body) is a thread row, not a comment, so it
 *  can't be a quote target yet (deferred — see comment-quotes.md §7). */
function readSelection() {
  const sel = window.getSelection();
  if (!sel || sel.isCollapsed) return null;
  const text = sel.toString().trim();
  if (!text) return null;
  let node = sel.getRangeAt(0).startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  const body = node && node.closest ? node.closest(".reply .body") : null;
  const source = body ? body.closest("[data-reply-id]") : null;
  return source ? { text, source } : null;
}

function visible(textarea) {
  if (!textarea || !textarea.isConnected) return false;
  const details = textarea.closest("details");
  return !details || details.open; // an inline composer must be open; the bottom composer has no <details>
}

/** Smart destination: the composer you're typing in if it's open, else open the source's inline reply. */
function destinationFor(sourceComment) {
  if (visible(lastComposerText)) return lastComposerText;
  const details = sourceComment.querySelector("details.reply__compose");
  if (!details) return null;
  details.open = true;
  return details.querySelector("[data-composer-text]");
}

function quoteInto(sel) {
  if (!sel) return hideToolbar();
  const dest = destinationFor(sel.source);
  if (!dest) return hideToolbar();
  dest.value = appendQuote(dest.value, sel.text);
  dest.dispatchEvent(new Event("input", { bubbles: true })); // app.js autosize + state sync
  dest.focus();
  const form = dest.closest("form[data-composer]");
  if (form) {
    const list = pendingByForm.get(form) || [];
    list.push({ targetId: sel.source.getAttribute("data-reply-id"), text: sel.text });
    pendingByForm.set(form, list);
  }
  hideToolbar();
  window.getSelection()?.removeAllRanges();
}

function hideToolbar() {
  if (toolbarEl) {
    toolbarEl.remove();
    toolbarEl = null;
  }
  captured = null;
}

/** Position the toolbar centred above the selection (flipping below if it'd clip the top), clamped to
 *  the viewport. Fixed positioning, so the selection's viewport-relative rect is what we anchor to. */
function placeToolbar() {
  if (!toolbarEl) return;
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;
  const rect = sel.getRangeAt(0).getBoundingClientRect();
  const r = toolbarEl.getBoundingClientRect();
  let top = rect.top - r.height - 8;
  if (top < 8) top = rect.bottom + 8;
  let left = rect.left + rect.width / 2 - r.width / 2;
  left = Math.max(8, Math.min(left, window.innerWidth - r.width - 8));
  toolbarEl.style.top = top + "px";
  toolbarEl.style.left = left + "px";
}

function showToolbar(sel) {
  captured = sel;
  if (!toolbarEl) {
    toolbarEl = document.createElement("div");
    toolbarEl.className = "quote-toolbar";
    toolbarEl.setAttribute("data-quote-toolbar", "");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "quote-toolbar__item";
    btn.setAttribute("data-quote-action", "");
    btn.textContent = "❝ Quote";
    // preventDefault on mousedown keeps the button from stealing focus and collapsing the selection, so
    // it survives to the click handler (which prefers the live selection, falling back to the snapshot).
    btn.addEventListener("mousedown", (e) => e.preventDefault());
    btn.addEventListener("click", () => quoteInto(readSelection() || captured));
    toolbarEl.appendChild(btn);
    document.body.appendChild(toolbarEl);
  }
  placeToolbar();
}

// Re-evaluate after a gesture settles: show the toolbar for a real selection in a comment, hide it
// otherwise. mouseup covers drag / double-click / triple-click; the keyup branch covers keyboard select.
function onGesture(e) {
  if (e && e.target && e.target.closest && e.target.closest("[data-quote-toolbar]")) return;
  const sel = readSelection();
  if (sel) showToolbar(sel);
  else hideToolbar();
}

// ---- listeners ----

document.addEventListener("mouseup", onGesture);
document.addEventListener("keyup", (e) => {
  if (e.key === "Shift" || e.key.startsWith("Arrow")) onGesture(e);
});
// Collapsing the selection (a plain click, typing, Esc-deselect) tears the toolbar down.
document.addEventListener("selectionchange", () => {
  const sel = window.getSelection();
  if (!sel || sel.isCollapsed || !sel.toString().trim()) hideToolbar();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") hideToolbar();
});
window.addEventListener("scroll", placeToolbar, true); // follow the selection while scrolling
window.addEventListener("resize", placeToolbar);
document.addEventListener("focusin", (e) => {
  const t = e.target.closest ? e.target.closest("[data-composer-text]") : null;
  if (t) lastComposerText = t;
});

// Attach the pending quotes to the composer's POST. Separate from app.js's configRequest handler (which
// rewrites the path for note/ask) — both run, different concerns. parameters works for urlencoded and
// multipart alike.
document.body.addEventListener("htmx:configRequest", (evt) => {
  const elt = evt.detail && evt.detail.elt;
  const form = elt && elt.closest ? elt.closest("form[data-composer]") : null;
  if (!form) return;
  const json = serializeQuotes(pendingByForm.get(form));
  if (json) evt.detail.parameters.quotesJson = json;
});

// Once the reply posts, the edges are recorded server-side — drop the pending list so a later message on
// the same composer doesn't re-send them.
document.body.addEventListener("htmx:afterRequest", (evt) => {
  const elt = evt.detail && evt.detail.elt;
  const form = elt && elt.closest ? elt.closest("form[data-composer]") : null;
  if (form && evt.detail.successful) pendingByForm.delete(form);
});
