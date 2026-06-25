import { test } from "node:test";
import assert from "node:assert/strict";
import {
  noticeFor,
  SEND_ERROR,
  ERROR_EVENT,
} from "../../main/resources/static/htmx-error-core.mjs";

/*
 * The frontend "Tier 0" for honest htmx failure handling (T1.4): the pure notice decision behind the
 * global error toast. htmx 2.0.6 already re-enables disabled controls / clears spinners on its own (so
 * there is no re-enable decision to test), and on a non-2xx it discards the body — the server advice
 * works around that by returning the fragment at 200 + an app:error HX-Trigger. This proves the toast
 * copy for that signal and for a network sendError. The DOM glue (htmx-error.js) is manually verified.
 */

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
