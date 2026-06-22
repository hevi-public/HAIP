/*
 * composer-core — pure note/ask mode model for the composer footer toggle. NO DOM, NO globals.
 *
 * The composer posts to /note (a silent, visible owner comment that flows into context but summons no
 * one) in "note" mode — the DEFAULT — or to /generate (summon a persona) in "ask" mode. One footer
 * button alternates between the two; typing /ask (or tagging a persona) summons. This module is the
 * unit-tested decision layer behind that toggle (src/test/js/composer-core.test.mjs); the DOM glue
 * that reads the form's current mode and rewires the endpoint + relabels the buttons lives in app.js.
 */

/**
 * New mode after a slash command fires.
 *  - /note            → note mode (post without summoning)
 *  - /ask             → ask mode (back to summoning)
 *  - /branch, /topic  → ask mode: they set the generation scope, which only applies to a real summon,
 *                       so picking one also drops you out of note mode
 *  - anything else    → unchanged (e.g. an @mention pick)
 */
export function reduceMode(mode, cmd) {
  if (cmd === "note") return "note";
  if (cmd === "ask" || cmd === "branch" || cmd === "topic") return "ask";
  return mode;
}

/**
 * The mode a submit will ACT in, given the toggle mode and whether the message tags a persona.
 * Tagging a persona always summons (ask), even from the note default — naming someone (an @mention,
 * or selecting their chip) is a deliberate request for their reply, so it overrides note mode.
 */
export function effectiveMode(mode, tagged) {
  return tagged ? "ask" : mode;
}

/** Submit-button label for the mode — what pressing it will do. */
export function submitLabel(mode) {
  return mode === "note" ? "Note ▸" : "Ask ▸";
}

/** The footer toggle shows the OTHER mode — the action it switches you to. */
export function toggleLabel(mode) {
  return mode === "note" ? "/ask" : "/note";
}

/** The slash command the footer toggle fires in the current mode (the inverse of `mode`). */
export function toggleCmd(mode) {
  return mode === "note" ? "ask" : "note";
}

/**
 * Rewrite a /generate request URL to its /note sibling — the actual "summon or not" switch.
 *
 * Note mode can't just flip the form's hx-post attribute: htmx caches the path when it processes the
 * form, so a post-hoc setAttribute is ignored and the request still hits /generate (summoning the
 * personas). Instead app.js rewrites the URL at htmx:configRequest time with this. Only the trailing
 * `/generate` path segment flips, and any query string is preserved.
 */
export function notePath(path) {
  return path.replace(/\/generate(\?|$)/, "/note$1");
}

/**
 * The endpoint a submit should hit, given the toggle mode and whether the message tags a persona.
 * Note mode (the default) redirects to /note — UNLESS a persona is tagged, which always summons, so
 * the request stays on /generate. Ask mode always summons. The form's static hx-post is /generate, so
 * this only ever rewrites it down to /note; ask is the no-op that leaves the canonical path intact.
 */
export function resolvePath(path, mode, tagged) {
  return effectiveMode(mode, tagged) === "note" ? notePath(path) : path;
}
