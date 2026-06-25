import { test } from "node:test";
import assert from "node:assert/strict";
import {
  noticeFor,
  addToast,
  dismissToast,
  listToasts,
  MAX_TOASTS,
  SEND_ERROR,
  ERROR_EVENT,
} from "../../main/resources/static/htmx-error-core.mjs";

/*
 * The frontend "Tier 0" for honest htmx failure handling (T1.4): the toast WORDING (noticeFor) and the
 * sticky toast STORE (add/dismiss/list, cap + de-dupe, persistence/rehydration). htmx 2.0.6 re-enables
 * controls / clears spinners itself and discards a non-2xx body — so the server returns the real error
 * status + an app:error HX-Trigger and nothing swaps; the toast is the sole feedback. The DOM glue
 * (htmx-error.js) wires real localStorage + the ✕ button and is manually verified.
 */

// A fake of the injectable storage interface the core writes through: an in-memory cell that survives
// being re-read by a "fresh" store (the same backing object) — exactly how localStorage rehydrates.
function fakeStorage(initial) {
  let cell = initial === undefined ? null : initial;
  return {
    read: () => cell,
    write: (toasts) => { cell = toasts; },
    // peek at raw backing for rehydration assertions
    _raw: () => cell,
  };
}

const rec = (id, kind, status) => ({ id, kind, status, message: noticeFor(kind, status) });

// ---- noticeFor wording ---------------------------------------------------------------------------

test("a send error (request never left) explains the connection problem", () => {
  assert.equal(
    noticeFor(SEND_ERROR, null),
    "Couldn't reach the server — check your connection and try again.",
  );
});

test("an app:error carrying a rate-limit status (429 / 503) says the model is busy", () => {
  assert.equal(noticeFor(ERROR_EVENT, 429), "The model is busy right now — try again in a moment.");
  assert.equal(noticeFor(ERROR_EVENT, 503), "The model is busy right now — try again in a moment.");
});

test("an app:error with a 5xx status is a server error", () => {
  assert.equal(noticeFor(ERROR_EVENT, 500), "Something went wrong on the server — please try again.");
  assert.equal(noticeFor(ERROR_EVENT, 502), "Something went wrong on the server — please try again.");
  assert.equal(noticeFor(ERROR_EVENT, 504), "Something went wrong on the server — please try again.");
});

test("an app:error with a 4xx status (other than rate-limit) is a request problem", () => {
  assert.equal(noticeFor(ERROR_EVENT, 400), "That request couldn't be completed — please try again.");
  assert.equal(noticeFor(ERROR_EVENT, 404), "That request couldn't be completed — please try again.");
});

test("a missing / non-numeric status falls back to a generic notice", () => {
  assert.equal(noticeFor(ERROR_EVENT, null), "Something went wrong — please try again.");
  assert.equal(noticeFor(ERROR_EVENT, undefined), "Something went wrong — please try again.");
  assert.equal(noticeFor("app:somethingElse", null), "Something went wrong — please try again.");
});

// ---- toast store: add / list / dismiss -----------------------------------------------------------

test("a fresh store lists nothing", () => {
  assert.deepEqual(listToasts(fakeStorage()), []);
});

test("addToast persists a toast and listToasts returns it", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 502));
  const got = listToasts(s);
  assert.equal(got.length, 1);
  assert.equal(got[0].id, "a");
  assert.equal(got[0].status, 502);
});

test("distinct toasts accumulate oldest-first", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 502));
  addToast(s, rec("b", ERROR_EVENT, 503));
  addToast(s, rec("c", SEND_ERROR, null));
  assert.deepEqual(listToasts(s).map((t) => t.id), ["a", "b", "c"]);
});

test("dismissToast removes the named toast, leaving the rest", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 502));
  addToast(s, rec("b", ERROR_EVENT, 503));
  dismissToast(s, "a");
  assert.deepEqual(listToasts(s).map((t) => t.id), ["b"]);
});

test("dismissing an unknown id is a no-op", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 502));
  dismissToast(s, "nope");
  assert.deepEqual(listToasts(s).map((t) => t.id), ["a"]);
});

// ---- de-dupe + cap -------------------------------------------------------------------------------

test("a consecutive duplicate (same kind+status) collapses instead of stacking", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 503));
  addToast(s, rec("b", ERROR_EVENT, 503)); // retried, still rate-limited
  const got = listToasts(s);
  assert.equal(got.length, 1, "the retry should not stack a second identical toast");
  assert.equal(got[0].id, "b", "the newcomer's id wins (the latest occurrence)");
});

test("a different status after a duplicate is NOT collapsed", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 503));
  addToast(s, rec("b", ERROR_EVENT, 502));
  assert.deepEqual(listToasts(s).map((t) => t.id), ["a", "b"]);
});

test("the same status that is NOT the most recent does not collapse (only consecutive)", () => {
  const s = fakeStorage();
  addToast(s, rec("a", ERROR_EVENT, 502));
  addToast(s, rec("b", ERROR_EVENT, 503));
  addToast(s, rec("c", ERROR_EVENT, 502)); // matches "a" but not the last entry
  assert.deepEqual(listToasts(s).map((t) => t.id), ["a", "b", "c"]);
});

test("the store is capped at MAX_TOASTS, dropping the oldest", () => {
  const s = fakeStorage();
  for (let i = 0; i < MAX_TOASTS + 3; i++) {
    addToast(s, rec("id" + i, ERROR_EVENT, 500 + i)); // distinct statuses so none collapse
  }
  const got = listToasts(s);
  assert.equal(got.length, MAX_TOASTS);
  // the first 3 were dropped; the tail survives in order
  assert.equal(got[0].id, "id3");
  assert.equal(got[got.length - 1].id, "id" + (MAX_TOASTS + 2));
});

// ---- rehydration ---------------------------------------------------------------------------------

test("a fresh store over the same backing reads the persisted toasts (rehydration)", () => {
  const s1 = fakeStorage();
  addToast(s1, rec("a", ERROR_EVENT, 502));
  addToast(s1, rec("b", SEND_ERROR, null));
  // Simulate a page refresh: a brand-new store object reading the SAME underlying cell.
  const s2 = fakeStorage(s1._raw());
  assert.deepEqual(listToasts(s2).map((t) => t.id), ["a", "b"]);
});

test("listToasts tolerates a corrupt/empty backing", () => {
  assert.deepEqual(listToasts(fakeStorage(null)), []);
  assert.deepEqual(listToasts(fakeStorage("not-an-array")), []);
});
