/*
 * htmx-error-core — pure decision layer for honest htmx failure handling (T1.4).
 *
 * NO DOM, NO globals: this one function decides the non-blocking notice to surface for a failed htmx
 * interaction. The DOM glue (htmx-error.js, loaded directly from layout.kte) listens for the events and
 * shows the toast. See src/test/js/htmx-error-core.test.mjs.
 *
 * What htmx 2.0.6 already does (verified against the vendored dist/htmx.js, so we DON'T re-do it):
 *   - It re-enables `hx-disabled-elt` controls and clears `hx-indicator` spinners on EVERY terminal xhr
 *     path — onload (incl. non-2xx), onerror (sendError), ontimeout — via removeRequestIndicators(). So
 *     there is no "stuck control" to fix on the client; that belief was wrong.
 *   - On a non-2xx it does NOT swap the body (default responseHandling: [45].. → swap:false). Our server
 *     advice works around that by returning the error fragment at HTTP 200 (so htmx swaps it) and
 *     signalling the real failure out-of-band via an `app:error` HX-Trigger event.
 *
 * What htmx does NOT do, and is the load-bearing reason this module exists: it gives NO default
 * user-visible feedback when a request fails. So the genuinely useful client piece is a toast, raised
 * for (a) the server-error signal (`app:error`, carrying the mapped status) and (b) `htmx:sendError`
 * (the request never reached the server — there's no response and nothing swaps, so the fragment can't
 * speak for itself).
 */

// The htmx event for a request that never reached the server (network failure). No response, no swap.
export const SEND_ERROR = "htmx:sendError";
// Our server's out-of-band failure signal, dispatched by htmx from the advice's HX-Trigger header.
export const ERROR_EVENT = "app:error";

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
