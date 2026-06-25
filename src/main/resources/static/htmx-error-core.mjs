/*
 * htmx-error-core — pure decision + persistence layer for honest htmx failure handling (T1.4).
 *
 * NO DOM, NO globals, NO clock/random: the toast wording (`noticeFor`) and the sticky toast STORE
 * (add/dismiss/list over an injectable storage) live here and are unit-tested. The DOM glue
 * (htmx-error.js, loaded directly from layout.kte) wires real localStorage + the DOM + the ✕ button +
 * load-time rehydration, and supplies real ids. See src/test/js/htmx-error-core.test.mjs.
 *
 * The toast-only design rests on htmx-2.0.6 behaviour verified against the vendored dist/htmx.js:
 *   - On a non-2xx htmx does NOT swap the body (default responseHandling: [45].. → swap:false), so the
 *     server returns the real error status and NOTHING lands in the compose field.
 *   - It still processes `HX-Trigger` (at the top of handleAjaxResponse, before swap/error branches), so
 *     the server fires `app:error` regardless of status — the client toasts off it.
 *   - It re-enables `hx-disabled-elt` controls and clears spinners itself (removeRequestIndicators in
 *     xhr.onload/onerror/ontimeout), so there is no stuck control to fix here.
 * The one thing htmx gives no default for is user-visible failure feedback — that is this module's job.
 */

// The htmx event for a request that never reached the server (network failure). No response, no swap.
export const SEND_ERROR = "htmx:sendError";
// Our server's out-of-band failure signal, dispatched by htmx from the advice's HX-Trigger header.
export const ERROR_EVENT = "app:error";

// Most recent N toasts are kept; older ones are dropped so storage can't grow unbounded.
export const MAX_TOASTS = 5;

/**
 * The owner-facing notice for a failed htmx interaction.
 *
 * @param {string} eventType   the event name (SEND_ERROR | ERROR_EVENT)
 * @param {number|null} status the mapped HTTP status carried by an app:error event, else null
 * @returns {string} a short, non-blocking message
 */
export function noticeFor(eventType, status) {
  if (eventType === SEND_ERROR) {
    return "Couldn't reach the server — check your connection and try again.";
  }
  // app:error (or anything else with a status): word it from the mapped status the server sent.
  if (status === 429 || status === 503) {
    return "The model is busy right now — try again in a moment.";
  }
  if (typeof status === "number" && status >= 500) {
    return "Something went wrong on the server — please try again.";
  }
  if (typeof status === "number" && status >= 400) {
    return "That request couldn't be completed — please try again.";
  }
  return "Something went wrong — please try again.";
}

/*
 * The sticky toast STORE. `storage` is an injectable interface — `{ read(): Toast[], write(toasts) }` —
 * so the glue can back it with localStorage while tests back it with a plain in-memory object. A Toast is
 * `{ id, kind, status, message }`; the glue mints `id` (the core never calls Date.now()/Math.random()).
 * Rehydration is implicit: a fresh store over the same storage simply `read()`s what's there.
 */

/** The active toasts, oldest-first. Tolerates a missing/corrupt store by treating it as empty. */
export function listToasts(storage) {
  const raw = storage.read();
  return Array.isArray(raw) ? raw : [];
}

/**
 * Add [toast] (append), then enforce de-dupe + cap, and persist. Returns the new active list.
 *   - De-dupe: if the most recent existing toast has the same kind+status, replace it rather than stack
 *     (a retried-and-still-failing call shouldn't pile identical toasts). The newcomer's id wins.
 *   - Cap: keep only the most recent MAX_TOASTS, dropping the oldest.
 */
export function addToast(storage, toast) {
  let toasts = listToasts(storage).slice();
  const last = toasts[toasts.length - 1];
  if (last && last.kind === toast.kind && last.status === toast.status) {
    toasts[toasts.length - 1] = toast; // collapse the consecutive duplicate
  } else {
    toasts.push(toast);
  }
  if (toasts.length > MAX_TOASTS) {
    toasts = toasts.slice(toasts.length - MAX_TOASTS);
  }
  storage.write(toasts);
  return toasts;
}

/** Remove the toast with [id] and persist. Returns the new active list (unchanged if id is absent). */
export function dismissToast(storage, id) {
  const toasts = listToasts(storage).filter((t) => t.id !== id);
  storage.write(toasts);
  return toasts;
}
