/*
 * htmx-error.js — DOM glue for honest htmx failure handling (T1.4). The notice copy lives in
 * htmx-error-core.mjs and is unit-tested; this file is the (manually-verified) wiring. Loaded directly
 * as an ES module from layout.kte (<script type="module" src="/htmx-error.js">), like the other glue.
 *
 * htmx 2.0.6 already re-enables hx-disabled-elt controls and clears hx-indicator spinners on every
 * terminal request path (verified against the vendored dist/htmx.js), so there is nothing to re-enable
 * here. What htmx gives NO default for is user-visible feedback that a request failed — so this raises a
 * non-blocking toast for the two surfaces that need it:
 *   - app:error      — the server's out-of-band failure signal (HX-Trigger from HtmxErrorAdvice). The
 *                      error fragment itself is swapped into the target at HTTP 200; this toast is the
 *                      always-visible companion (the fragment slot may be off-screen). detail = {status, message}.
 *   - htmx:sendError — the request never reached the server (network failure): no response, nothing
 *                      swaps, so the fragment can't speak for itself and the toast is the only feedback.
 */
import { noticeFor, SEND_ERROR, ERROR_EVENT } from "./htmx-error-core.mjs";

(function () {
  // A self-dismissing toast in a shared live region — non-blocking, never steals focus. role="status"
  // already implies aria-live="polite", so we don't set aria-live too (a contradictory explicit value
  // would override the role's politeness). Created lazily on first error.
  function toast(message) {
    var region = document.querySelector("[data-error-toasts]");
    if (!region) {
      region = document.createElement("div");
      region.setAttribute("data-error-toasts", "");
      region.className = "error-toasts";
      region.setAttribute("role", "status");
      document.body.appendChild(region);
    }
    var note = document.createElement("div");
    note.className = "error-toast";
    note.setAttribute("data-error-toast", "");
    note.textContent = message;
    region.appendChild(note);
    setTimeout(function () { note.remove(); }, 6000);
  }

  // The server-error signal carries the mapped status in its detail; word the toast from it.
  document.body.addEventListener(ERROR_EVENT, function (e) {
    var status = e.detail && typeof e.detail.status === "number" ? e.detail.status : null;
    toast(noticeFor(ERROR_EVENT, status));
  });

  // A request that never left (no response, no swap): the toast is the only feedback htmx leaves room for.
  document.body.addEventListener(SEND_ERROR, function () {
    toast(noticeFor(SEND_ERROR, null));
  });
})();
