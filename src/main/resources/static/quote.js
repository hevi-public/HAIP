/*
 * quote.js — the "Quote" context menu over comment bodies (plan_docs/comment-quotes.md, forward slice).
 *
 * Select text in a comment, right-click → Quote: the selection is inserted as a markdown blockquote into
 * a composer (the one you're already typing in, else a fresh inline reply opened under the quoted
 * comment) and a quote edge (this reply → the source comment) is recorded by attaching `quotesJson` to
 * the composer's POST at htmx:configRequest time. The forward "↗ author" anchor then renders server-side.
 *
 * Pure logic (blockquote building, serialisation) lives in quote-core.mjs (unit-tested); this is DOM glue
 * and is intentionally untested by the HTTP suite (same split as nav.js). Progressive enhancement: with
 * JS off there's no menu, but a hand-typed `> ` blockquote still posts — just without the link.
 */
import { appendQuote, serializeQuotes } from "./quote-core.mjs";

// Pending quotes per composer form, drained into quotesJson at submit time and cleared after it settles.
const pendingByForm = new WeakMap();
// The composer textarea the owner most recently focused — the "active" destination when one is open.
let lastComposerText = null;
let menuEl = null;

function selectionText() {
  const sel = window.getSelection();
  return sel && !sel.isCollapsed ? sel.toString().trim() : "";
}

/** The comment node the current selection STARTS in, if it's inside a comment body (never the OP). */
function sourceCommentOf() {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return null;
  let node = sel.getRangeAt(0).startContainer;
  if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
  // Scope to `.reply .body`: the OP body (.thread__body) is a thread row, not a comment, so it can't be
  // a quote target yet (no comment id to link to) — deferred (see comment-quotes.md §7).
  const body = node && node.closest ? node.closest(".reply .body") : null;
  return body ? body.closest("[data-reply-id]") : null;
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

function quoteInto(sourceComment) {
  const text = selectionText();
  if (!text) return hideMenu();
  const dest = destinationFor(sourceComment);
  if (!dest) return hideMenu();
  dest.value = appendQuote(dest.value, text);
  dest.dispatchEvent(new Event("input", { bubbles: true })); // app.js autosize + state sync
  dest.focus();
  const form = dest.closest("form[data-composer]");
  if (form) {
    const list = pendingByForm.get(form) || [];
    list.push({ targetId: sourceComment.getAttribute("data-reply-id"), text });
    pendingByForm.set(form, list);
  }
  hideMenu();
  window.getSelection()?.removeAllRanges();
}

function hideMenu() {
  if (menuEl) {
    menuEl.remove();
    menuEl = null;
  }
}

function showMenu(x, y, sourceComment) {
  hideMenu();
  menuEl = document.createElement("div");
  menuEl.className = "quote-menu";
  menuEl.setAttribute("data-quote-menu", "");
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "quote-menu__item";
  btn.setAttribute("data-quote-action", "");
  btn.textContent = "❝ Quote";
  btn.addEventListener("click", () => quoteInto(sourceComment));
  menuEl.appendChild(btn);
  document.body.appendChild(menuEl);
  // Clamp to the viewport so the menu never spills off the edge.
  const r = menuEl.getBoundingClientRect();
  menuEl.style.left = Math.min(x, window.innerWidth - r.width - 8) + "px";
  menuEl.style.top = Math.min(y, window.innerHeight - r.height - 8) + "px";
}

function onContextMenu(e) {
  if (e.target.closest(".composer")) return; // leave the native menu inside a composer
  if (!selectionText()) return; // no selection → native menu (copy, inspect, …)
  const source = sourceCommentOf();
  if (!source) return;
  e.preventDefault();
  showMenu(e.clientX, e.clientY, source);
}

// ---- listeners ----

document.addEventListener("contextmenu", onContextMenu);
document.addEventListener("click", (e) => {
  if (menuEl && !e.target.closest("[data-quote-menu]")) hideMenu();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") hideMenu();
});
window.addEventListener("scroll", hideMenu, true);
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
