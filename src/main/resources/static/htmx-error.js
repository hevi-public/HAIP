/*
 * htmx-error.js — DOM glue for honest htmx failure handling (T1.4). The wording (noticeFor), the relative
 * age label (ageLabel), the sticky toast store (add/dismiss/list + cap + de-dupe), and the TTL-pruning
 * best-effort localStorage backing (toastStorage) live in htmx-error-core.mjs and are unit-tested; this
 * file is the thin, manually-verified wiring: the DOM region, the ✕ dismiss button, focus handling, the
 * shared age-refresh timer, and load-time rehydration. Loaded directly as an ES module from layout.kte.
 *
 * Toast-only by design (verified against vendored htmx 2.0.6): on a non-2xx htmx swaps nothing, so the
 * server returns the real error status + an app:error HX-Trigger and NOTHING lands in the compose field;
 * htmx also re-enables hx-disabled-elt controls itself, so there is no stuck control to fix. The toast is
 * the sole, honest feedback, and it's STICKY (no auto-dismiss) + persisted across refresh (best-effort,
 * TTL-bounded) until the owner clicks ✕. It carries a live "time elapsed" label. Two surfaces raise one:
 *   - app:error      — the server's failure signal. detail = {status} only (HX-Trigger header values must
 *                      be ASCII, no em dashes), so the toast is worded from the status alone.
 *   - htmx:sendError — the request never reached the server (network failure): no response, no swap.
 */
import { noticeFor, ageLabel, addToast, dismissToast, listToasts, toastStorage, SEND_ERROR, ERROR_EVENT } from "./htmx-error-core.mjs";

(function () {
  // TTL-pruning, best-effort localStorage backing. The clock is injected so the core's pruning stays
  // deterministic; here it's the real wall clock.
  var now = function () { return Date.now(); };
  var storage = toastStorage(window.localStorage, now);

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

  // Render one toast row (text · age + ✕). Idempotent per id, so a re-render (rehydrate) won't duplicate.
  function renderOne(rec) {
    if (document.querySelector('[data-error-toast="' + cssEscape(rec.id) + '"]')) return;
    var note = document.createElement("div");
    note.className = "error-toast";
    note.setAttribute("data-error-toast", rec.id);

    var text = document.createElement("span");
    text.className = "error-toast__text";
    text.textContent = rec.message;
    note.appendChild(text);

    // The live "· N minutes ago" suffix, refreshed by the shared timer below.
    var age = document.createElement("span");
    age.className = "error-toast__age";
    age.setAttribute("data-error-toast-age", rec.createdAt);
    age.textContent = ageSuffix(rec.createdAt);
    note.appendChild(age);

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

  function ageSuffix(createdAt) {
    return " · " + ageLabel(Number(createdAt), now());
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
    syncTimer();
  }

  // Add a toast for an error, persist it (with cap + de-dupe in the core), and render whatever the store
  // now holds — so a de-duped/collapsed toast updates in place rather than stacking.
  function raise(kind, status) {
    var rec = { id: nextId(), kind: kind, status: status, message: noticeFor(kind, status), createdAt: now() };
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
    refreshAges();
    syncTimer();
  }

  // ---- one shared ~60s timer refreshes every visible age (started on first toast, cleared on the last).
  var ageTimer = null;
  function refreshAges() {
    var ages = document.querySelectorAll("[data-error-toast-age]");
    for (var i = 0; i < ages.length; i++) {
      ages[i].textContent = ageSuffix(ages[i].getAttribute("data-error-toast-age"));
    }
  }
  function syncTimer() {
    var any = document.querySelector("[data-error-toast]");
    if (any && !ageTimer) {
      ageTimer = setInterval(refreshAges, 60000);
    } else if (!any && ageTimer) {
      clearInterval(ageTimer);
      ageTimer = null;
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

  // Create the region + rehydrate any (non-expired) toasts the owner hadn't dismissed before the last
  // refresh, once the body exists. rerender recomputes the ages and (re)starts the timer if any survive.
  function bootstrap() { ensureRegion(); rerender(); }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap);
  } else {
    bootstrap();
  }
})();
