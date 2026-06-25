import { test } from "node:test";
import assert from "node:assert/strict";
import {
  noticeFor,
  shouldReEnable,
  RESPONSE_ERROR,
  SEND_ERROR,
} from "../../main/resources/static/htmx-error-core.mjs";

/*
 * The frontend "Tier 0" for honest htmx failure handling (T1.4): the pure decisions behind the global
 * error listener — what notice to show and whether the triggering control must be re-enabled. The DOM
 * glue (htmx-error.js) is the manually-verified wiring; this proves the decision layer.
 */

test("a send error (request never left) explains the connection problem", () => {
  assert.equal(
    noticeFor(SEND_ERROR, null),
    "Couldn't reach the server — check your connection and try again.",
  );
});

test("rate-limit statuses (429 / 503) say the model is busy", () => {
  assert.equal(noticeFor(RESPONSE_ERROR, 429), "The model is busy right now — try again in a moment.");
  assert.equal(noticeFor(RESPONSE_ERROR, 503), "The model is busy right now — try again in a moment.");
});

test("a 5xx is a server error", () => {
  assert.equal(noticeFor(RESPONSE_ERROR, 500), "Something went wrong on the server — please try again.");
  assert.equal(noticeFor(RESPONSE_ERROR, 502), "Something went wrong on the server — please try again.");
  assert.equal(noticeFor(RESPONSE_ERROR, 504), "Something went wrong on the server — please try again.");
});

test("a 4xx (other than rate-limit) is a request problem", () => {
  assert.equal(noticeFor(RESPONSE_ERROR, 400), "That request couldn't be completed — please try again.");
  assert.equal(noticeFor(RESPONSE_ERROR, 404), "That request couldn't be completed — please try again.");
});

test("an unknown event / missing status falls back to a generic notice", () => {
  assert.equal(noticeFor("htmx:somethingElse", null), "Something went wrong — please try again.");
  assert.equal(noticeFor(RESPONSE_ERROR, null), "Something went wrong — please try again.");
});

test("a disabled form control is re-enabled", () => {
  // hx-disabled-elt="this" disabled the Regenerate <button> for the request; on error it stays disabled.
  assert.equal(shouldReEnable({ tagName: "BUTTON", disabled: true }), true);
  assert.equal(shouldReEnable({ tagName: "input", disabled: true }), true);
  assert.equal(shouldReEnable({ tagName: "fieldset", disabled: true }), true);
});

test("an already-enabled control is left alone", () => {
  assert.equal(shouldReEnable({ tagName: "BUTTON", disabled: false }), false);
});

test("a non-control element is never touched", () => {
  // The triggering element of a poll can be a <div> (hx-trigger=every 1s) — it has no `disabled`.
  assert.equal(shouldReEnable({ tagName: "DIV", disabled: true }), false);
  assert.equal(shouldReEnable({ tagName: "article" }), false);
});

test("a missing / malformed element is treated as 'do not re-enable'", () => {
  assert.equal(shouldReEnable(null), false);
  assert.equal(shouldReEnable(undefined), false);
  assert.equal(shouldReEnable({}), false);
});
