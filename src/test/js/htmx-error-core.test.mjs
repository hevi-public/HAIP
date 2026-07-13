import { test } from "node:test";
import assert from "node:assert/strict";
import {
  noticeFor,
  ageLabel,
  addToast,
  dismissToast,
  listToasts,
  toastStorage,
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

test("two consecutive sendErrors (null status) collapse to one (offline-retry de-dupe)", () => {
  const s = fakeStorage();
  addToast(s, rec("a", SEND_ERROR, null));
  addToast(s, rec("b", SEND_ERROR, null)); // retried while still offline
  const got = listToasts(s);
  assert.equal(got.length, 1, "repeated offline failures shouldn't stack identical toasts");
  assert.equal(got[0].id, "b", "the newcomer wins");
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

// ---- ageLabel (relative "time elapsed", with an injected now) ------------------------------------

const T0 = 1_700_000_000_000; // a fixed epoch-ms anchor for deterministic age tests

test("ageLabel reads 'just now' for sub-minute elapsed", () => {
  assert.equal(ageLabel(T0, T0), "just now");
  assert.equal(ageLabel(T0, T0 + 1000), "just now");        // 1s
  assert.equal(ageLabel(T0, T0 + 59 * 1000), "just now");   // 59s
});

test("ageLabel reads minutes for sub-hour elapsed", () => {
  assert.equal(ageLabel(T0, T0 + 60 * 1000), "1 minute ago");
  assert.equal(ageLabel(T0, T0 + 3 * 60 * 1000), "3 minutes ago");
  assert.equal(ageLabel(T0, T0 + 59 * 60 * 1000), "59 minutes ago");
});

test("ageLabel reads hours from an hour up", () => {
  assert.equal(ageLabel(T0, T0 + 60 * 60 * 1000), "1 hour ago");
  assert.equal(ageLabel(T0, T0 + 5 * 60 * 60 * 1000), "5 hours ago");
});

test("ageLabel never goes negative if the clock skews backwards", () => {
  assert.equal(ageLabel(T0, T0 - 5000), "just now");
});

// ---- toastStorage: TTL pruning + best-effort persistence -----------------------------------------

// A Web-Storage-like fake. `writeThrows` simulates Safari private mode / quota (setItem throws).
function fakeLocalStorage({ writeThrows = false } = {}) {
  const cells = {};
  return {
    setCalls: 0,
    getItem(k) { return Object.prototype.hasOwnProperty.call(cells, k) ? cells[k] : null; },
    setItem(k, v) {
      this.setCalls++;
      if (writeThrows) throw new Error("quota / private mode");
      cells[k] = v;
    },
  };
}

const trec = (id, status, createdAt) => ({ id, kind: ERROR_EVENT, status, message: noticeFor(ERROR_EVENT, status), createdAt });

test("toastStorage happy path: persists and a fresh store rehydrates", () => {
  const ls = fakeLocalStorage();
  let clock = T0;
  const s = toastStorage(ls, () => clock, "k");
  addToast(s, trec("a", 502, T0));
  // a brand-new store over the SAME localStorage (a page refresh) reads it back
  const s2 = toastStorage(ls, () => clock, "k");
  assert.deepEqual(listToasts(s2).map((t) => t.id), ["a"]);
});

test("toastStorage prunes a >24h toast on rehydration, keeps a <24h one", () => {
  const ls = fakeLocalStorage();
  // Seed the backing directly with an old + a fresh toast.
  const old = trec("old", 502, T0 - (25 * 60 * 60 * 1000)); // 25h ago
  const fresh = trec("fresh", 503, T0 - (1 * 60 * 60 * 1000)); // 1h ago
  ls.setItem("k", JSON.stringify([old, fresh]));
  const s = toastStorage(ls, () => T0, "k");
  assert.deepEqual(listToasts(s).map((t) => t.id), ["fresh"], "the 25h-old toast is pruned, the 1h one kept");
});

test("toastStorage prunes expired entries on write too", () => {
  const ls = fakeLocalStorage();
  let clock = T0;
  const s = toastStorage(ls, () => clock, "k");
  addToast(s, trec("a", 502, T0)); // created now
  clock = T0 + (25 * 60 * 60 * 1000); // advance 25h
  addToast(s, trec("b", 503, clock)); // a new toast; the write prunes the now-expired "a"
  assert.deepEqual(listToasts(s).map((t) => t.id), ["b"]);
});

test("best-effort persist: a throwing setItem doesn't break add/list (toast still lists this session)", () => {
  const ls = fakeLocalStorage({ writeThrows: true });
  const s = toastStorage(ls, () => T0, "k");
  // setItem throws inside write(); the call must not throw, and the toast must still list (in-session).
  assert.doesNotThrow(() => addToast(s, trec("a", 502, T0)));
  assert.deepEqual(listToasts(s).map((t) => t.id), ["a"], "the in-session toast still renders without persistence");
});
