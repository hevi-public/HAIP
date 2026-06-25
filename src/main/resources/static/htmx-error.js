/*
 * htmx-error.js — DOM glue for honest htmx failure handling (T1.4). The wording (noticeFor), the sticky
 * toast store (add/dismiss/list + cap + de-dupe), and the localStorage backing (with the in-memory
 * fallback latch) live in htmx-error-core.mjs and are unit-tested; this file is the thin,
 * manually-verified wiring: the DOM region, the ✕ dismiss button, focus handling, and load-time
 * rehydration. Loaded directly as an ES module from layout.kte, like the other glue.
 *
 * Toast-only by design (verified against vendored htmx 2.0.6): on a non-2xx htmx swaps nothing, so the
 * server returns the real error status + an app:error HX-Trigger and NOTHING lands in the compose field;
 * htmx also re-enables hx-disabled-elt controls itself, so there is no stuck control to fix. The toast is
 * the sole, honest feedback, and it's STICKY (no auto-dismiss) + persisted across refresh until the owner
 * clicks ✕. Two surfaces raise one:
 *   - app:error      — the server's failure signal. detail = {status} only (HX-Trigger header values must
 *                      be ASCII, no em dashes), so the toast is worded from the status alone.
 *   - htmx:sendError — the request never reached the server (network failure): no response, no swap.
 */
import { noticeFor, addToast, dismissToast, listToasts, localStorageBacking, SEND_ERROR, ERROR_EVENT } from "./htmx-error-core.mjs";

(function () {
  // One authoritative backing: real localStorage, degrading to in-memory if it's unavailable (private
  // mode / quota). The read/write-agree latch lives in the core (localStorageBacking), so a write fault
  // can't leave read() returning stale localStorage that hides a just-raised toast.
  var storage = localStorageBacking(window.localStorage);

  // Monotonic id for new toasts — unique within a page session (rehydrated toasts keep their stored id).
  var seq = 0;
  function nextId() { return "t" + Date.now() + "-" + (seq++); }

  // Create the live region ONCE, up front (empty): content added to a PRE-EXISTING alert region is
  // announced by screen readers, whereas a region created and filled in the same tick often isn't.
  // role="alert" is assertive — appropriate for an error — and already implies aria-live, so we set
  // neither aria-live nor a second role.
  var regionEl = null;
  function ensureRegion() {
    if (!regionEl) {
      regionEl = document.querySelector("[data-error-toasts]");
      if (!regionEl) {
        regionEl = document.createElement("div");
        regionEl.setAttribute("data-error-toasts", "");
        regionEl.className = "error-toasts";
        regionEl.setAttribute("role", "alert");
        document.body.appendChild(regionEl);
      }
    }
    return regionEl;
  }

  // Render one toast row (text + ✕). Idempotent per id, so a re-render (rehydrate) won't duplicate it.
  function renderOne(rec) {
    if (document.querySelector('[data-error-toast="' + cssEscape(rec.id) + '"]')) return;
    var note = document.createElement("div");
    note.className = "error-toast";
    note.setAttribute("data-error-toast", rec.id);

    var text = document.createElement("span");
    text.className = "error-toast__text";
    text.textContent = rec.message;
    note.appendChild(text);

    var close = document.createElement("button");
    close.type = "button";
    close.className = "error-toast__dismiss";
    // A contextual name so multiple toasts don't all read as a bare "Dismiss" to a screen reader.
    close.setAttribute("aria-label", "Dismiss: " + rec.message);
    close.setAttribute("data-error-toast-dismiss", rec.id);
    close.textContent = "✕"; // ✕
    close.addEventListener("click", function () { remove(rec.id); });
    note.appendChild(close);

    ensureRegion().appendChild(note);
  }

  function remove(id) {
    var node = document.querySelector('[data-error-toast="' + cssEscape(id) + '"]');
    // Move focus off the button we're about to remove so a keyboard dismiss doesn't dump focus to <body>:
    // prefer the next remaining ✕, else the previous, else a sensible fallback.
    if (node && node.contains(document.activeElement)) {
      var buttons = Array.prototype.slice.call(document.querySelectorAll("[data-error-toast-dismiss]"));
      var idx = buttons.indexOf(node.querySelector("[data-error-toast-dismiss]"));
      var next = buttons[idx + 1] || buttons[idx - 1] || null;
      if (next) next.focus();
      else if (document.body) document.body.focus && document.body.focus();
    }
    dismissToast(storage, id);
    if (node) node.remove();
  }

  // Add a toast for an error, persist it (with cap + de-dupe in the core), and render whatever the store
  // now holds — so a de-duped/collapsed toast updates in place rather than stacking.
  function raise(kind, status) {
    var rec = { id: nextId(), kind: kind, status: status, message: noticeFor(kind, status) };
    addToast(storage, rec);
    rerender();
  }

  // Reconcile the DOM to the stored list: render any missing, drop any DOM rows no longer stored.
  function rerender() {
    var toasts = listToasts(storage);
    var ids = {};
    toasts.forEach(function (t) { ids[t.id] = true; renderOne(t); });
    var rows = document.querySelectorAll("[data-error-toast]");
    for (var i = 0; i < rows.length; i++) {
      var id = rows[i].getAttribute("data-error-toast");
      if (!ids[id]) rows[i].remove();
    }
  }

  function cssEscape(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/["\\]/g, "\\$&");
  }

  // app:error: the server's failure signal carries the mapped status in its detail.
  document.body.addEventListener(ERROR_EVENT, function (e) {
    var status = e.detail && typeof e.detail.status === "number" ? e.detail.status : null;
    raise(ERROR_EVENT, status);
  });

  // htmx:sendError: the request never left (no response, no swap) — the toast is the only feedback.
  document.body.addEventListener(SEND_ERROR, function () {
    raise(SEND_ERROR, null);
  });

  // Create the region + rehydrate any toasts the owner hadn't dismissed before the last refresh, once the
  // body exists.
  function bootstrap() { ensureRegion(); rerender(); }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap);
  } else {
    bootstrap();
  }
})();
