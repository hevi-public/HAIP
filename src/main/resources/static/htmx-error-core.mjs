/*
 * htmx-error-core — pure decision layer for honest htmx failure handling (T1.4).
 *
 * NO DOM, NO globals: these functions decide, from the shape of a failed htmx event, (a) the
 * non-blocking notice to surface and (b) whether the triggering control must be re-enabled. The DOM
 * glue (htmx-error.js, wired from app.js) reads the event off the page, calls these, and applies the
 * result — re-enabling the stuck `hx-disabled-elt` button and showing the notice. See
 * src/test/js/htmx-error-core.test.mjs.
 *
 * Why this exists: when a request errors, htmx fires `htmx:responseError` (a non-2xx came back) or
 * `htmx:sendError` (the request never reached the server). In neither case does the normal swap run,
 * so a control disabled for the request's duration (`hx-disabled-elt`) never re-enables and any
 * spinner (`hx-indicator`) keeps spinning. The fix is to react to those two events ourselves.
 */

// The two htmx error events we own. A response came back non-2xx, or the request never left.
export const RESPONSE_ERROR = "htmx:responseError";
export const SEND_ERROR = "htmx:sendError";

/**
 * The owner-facing notice for a failed htmx event.
 *
 * @param {string} eventType        the htmx event name (RESPONSE_ERROR | SEND_ERROR | other)
 * @param {number|null} status      the HTTP status for a responseError, else null
 * @returns {string} a short, non-blocking message
 */
export function noticeFor(eventType, status) {
  if (eventType === SEND_ERROR) {
    return "Couldn't reach the server — check your connection and try again.";
  }
  // A response came back, but non-2xx. The server's error fragment (if any) is swapped in separately;
  // this notice is the always-present fallback so even an empty/odd body never leaves silence.
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

/**
 * Whether [el] is a control that htmx may have left disabled for the failed request and that we should
 * therefore re-enable. We re-enable form controls (buttons/inputs/etc.) that carry the `disabled`
 * property; non-controls (or already-enabled controls) are left alone. Pure: takes a minimal shape, not
 * a live element, so it's testable without a DOM.
 *
 * @param {{tagName?: string, disabled?: boolean}} el
 * @returns {boolean}
 */
export function shouldReEnable(el) {
  if (!el || typeof el.tagName !== "string") return false;
  // Only form controls have a meaningful `disabled` — and only re-enable one that IS disabled.
  if (!CONTROLS.has(el.tagName.toLowerCase())) return false;
  return el.disabled === true;
}

// The form controls hx-disabled-elt can disable for a request's duration.
const CONTROLS = new Set(["button", "input", "select", "textarea", "fieldset"]);
