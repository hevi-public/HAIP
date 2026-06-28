/*
 * quote-backlinks.js — the BACKWARD direction of the quote graph (plan_docs/comment-quotes.md §6).
 *
 * A comment that has been quoted carries an SSR ".reply__quoted-by" block — the no-JS fallback list of
 * who quoted it. This module promotes that data: for each distinct quoted passage it re-finds the text in
 * the comment body and wraps it in <mark class="quoted">, then on hover / focus shows a "cone" popover
 * listing the comments that quoted that passage (clicking one jumps there). Passages it can't re-find
 * (the body was edited since the quote was made) are left in the visible fallback list, so a backlink
 * never silently disappears.
 *
 * Pure matching lives in quote-backlinks-core.mjs (unit-tested); this is DOM glue, untested by the HTTP
 * suite (same split as nav.js). Progressive enhancement: with JS off the fallback list is the feature.
 */
import { matchPassage } from "./quote-backlinks-core.mjs";

let cone = null;
let hideTimer = null;

function textNodesIn(el) {
  const out = [];
  const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
  for (let n = walk.nextNode(); n; n = walk.nextNode()) out.push(n);
  return out;
}

/** Wrap [start,end) of a single text node in <mark class="quoted">; null if the range can't be wrapped. */
function wrap(node, start, end) {
  try {
    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    const mark = document.createElement("mark");
    mark.className = "quoted";
    mark.setAttribute("data-quote-backlink", "");
    range.surroundContents(mark); // safe for a single-text-node range
    return mark;
  } catch {
    return null; // crossed an element boundary — leave the fallback entry visible instead
  }
}

/** Find [passage] in [body]'s first text node that contains it (not already inside a mark); wrap + return. */
function placeMark(body, passage) {
  for (const node of textNodesIn(body)) {
    if (node.parentElement.closest("mark.quoted")) continue; // don't nest marks
    const hit = matchPassage(node.textContent, passage);
    if (hit) return wrap(node, hit.start, hit.end);
  }
  return null;
}

function hideCone() {
  if (cone) {
    cone.remove();
    cone = null;
  }
  document.querySelectorAll("mark.quoted.is-open").forEach((m) => m.classList.remove("is-open"));
}

function scheduleHide() {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(hideCone, 180);
}

function positionCone(mark) {
  if (!cone || !mark) return;
  const r = mark.getBoundingClientRect();
  const c = cone.getBoundingClientRect();
  let top = r.bottom + 6;
  if (top + c.height > window.innerHeight - 8) top = r.top - c.height - 6;
  const left = Math.max(8, Math.min(r.left, window.innerWidth - c.width - 8));
  cone.style.top = top + "px";
  cone.style.left = left + "px";
}

function showCone(mark, block) {
  clearTimeout(hideTimer);
  if (cone && cone.__mark === mark) return; // already open for this mark
  hideCone();
  cone = document.createElement("div");
  cone.className = "quote-cone";
  cone.__mark = mark;
  const quoters = block.querySelectorAll(".reply__backlink-quoter");
  const cap = document.createElement("div");
  cap.className = "quote-cone__cap";
  cap.textContent = quoters.length === 1 ? "quoted by" : `quoted by ${quoters.length}`;
  cone.appendChild(cap);
  quoters.forEach((a) => cone.appendChild(a.cloneNode(true)));
  cone.addEventListener("mouseenter", () => clearTimeout(hideTimer));
  cone.addEventListener("mouseleave", scheduleHide);
  // Navigate on a quoter click, then dismiss (set the hash ourselves — removing the node would cancel the
  // anchor's own default navigation).
  cone.addEventListener("click", (e) => {
    const a = e.target.closest("a[href^='#']");
    if (a) {
      e.preventDefault();
      location.hash = a.getAttribute("href").slice(1);
    }
    hideCone();
  });
  document.body.appendChild(cone);
  mark.classList.add("is-open");
  positionCone(mark);
}

function enhance(reply) {
  if (reply.hasAttribute("data-backlinks-enhanced")) return;
  const block = reply.querySelector(":scope > .reply__quoted-by");
  const body = reply.querySelector(":scope > .body");
  if (!block || !body) return;
  reply.setAttribute("data-backlinks-enhanced", "");
  let anyFallbackLeft = false;
  block.querySelectorAll(":scope > .reply__backlink").forEach((bl) => {
    const mark = placeMark(body, bl.getAttribute("data-backlink-text") || "");
    if (!mark) {
      anyFallbackLeft = true; // couldn't re-find it — keep the visible fallback entry
      return;
    }
    bl.hidden = true; // now represented by the inline mark + cone
    mark.tabIndex = 0;
    const open = () => showCone(mark, bl);
    mark.addEventListener("mouseenter", open);
    mark.addEventListener("mouseleave", scheduleHide);
    mark.addEventListener("focus", open);
    mark.addEventListener("click", (e) => {
      const links = bl.querySelectorAll(".reply__backlink-quoter");
      if (links.length === 1) {
        e.preventDefault();
        location.hash = links[0].getAttribute("href").slice(1);
        hideCone();
      } else {
        open();
      }
    });
  });
  // Every passage became an inline mark → the SSR fallback list is redundant; hide it.
  if (!anyFallbackLeft) block.hidden = true;
}

function enhanceAll(root) {
  const scope = root && root.querySelectorAll ? root : document;
  scope.querySelectorAll(".reply[data-reply-id]").forEach(enhance);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => enhanceAll(document));
} else {
  enhanceAll(document);
}
// Re-rendered nodes (poll-settle, regenerate, edit, new replies) arrive without marks — re-scan; the
// per-reply guard makes already-enhanced nodes a no-op.
document.body.addEventListener("htmx:afterSwap", () => enhanceAll(document));
document.addEventListener("keydown", (e) => { if (e.key === "Escape") hideCone(); });
window.addEventListener("scroll", () => { if (cone) positionCone(cone.__mark); }, true);
window.addEventListener("resize", () => { if (cone) positionCone(cone.__mark); });
