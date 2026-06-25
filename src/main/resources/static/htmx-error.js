/*
 * htmx-error.js — DOM glue for honest htmx failure handling (T1.4). The wording (noticeFor) and the
 * sticky toast store (add/dismiss/list + cap + de-dupe over an injectable storage) live in
 * htmx-error-core.mjs and are unit-tested; this file is the thin, manually-verified wiring: real
 * localStorage, the DOM, the ✕ dismiss button, and load-time rehydration. Loaded directly as an ES
 * module from layout.kte (<script type="module" src="/htmx-error.js">), like the other glue.
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
import { noticeFor, addToast, dismissToast, listToasts, SEND_ERROR, ERROR_EVENT } from "./htmx-error-core.mjs";

(function () {
  var STORAGE_KEY = "haip.errorToasts";

  // The injectable storage the core writes through — backed by localStorage, degrading to in-memory if
  // it's unavailable (private mode / quota) so a toast still shows for the session.
  var memory = null;
  var storage = {
    read: function () {
      try {
        var raw = window.localStorage.getItem(STORAGE_KEY);
        return raw ? JSON.parse(raw) : [];
      } catch (e) {
        return memory || [];
      }
    },
    write: function (toasts) {
      memory = toasts;
      try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(toasts)); } catch (e) { /* keep memory */ }
    },
  };

  // Monotonic id for new toasts — unique within a page session; combined with content so a rehydrated
  // toast keeps its stored id (the core preserves ids; only freshly-added toasts get a new one here).
  var seq = 0;
  function nextId() { return "t" + Date.now() + "-" + (seq++); }

  function region() {
    var el = document.querySelector("[data-error-toasts]");
    if (!el) {
      el = document.createElement("div");
      el.setAttribute("data-error-toasts", "");
      el.className = "error-toasts";
      // role="status" implies aria-live="polite"; we don't set aria-live too (a contradictory explicit
      // value would override the role's politeness).
      el.setAttribute("role", "status");
      document.body.appendChild(el);
    }
    return el;
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
    close.setAttribute("aria-label", "Dismiss");
    close.setAttribute("data-error-toast-dismiss", rec.id);
    close.textContent = "✕"; // ✕
    close.addEventListener("click", function () { remove(rec.id); });
    note.appendChild(close);

    region().appendChild(note);
  }

  function remove(id) {
    dismissToast(storage, id);
    var node = document.querySelector('[data-error-toast="' + cssEscape(id) + '"]');
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

  // Rehydrate any toasts the owner hadn't dismissed before the last refresh.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", rerender);
  } else {
    rerender();
  }
})();
