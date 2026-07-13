/*
 * persona-form-core — pure staleness model for the persona create/edit form.
 *
 * NO DOM, NO globals: classify a changed control and reduce the form's `stale` flag. This is the
 * unit-tested heart of the prompt/dials divergence guard (see src/test/js/persona-form-core.test.mjs
 * and plan_docs/persona-prompt-edit-ux.md). The DOM glue (persona-form.js) reads control names + prompt
 * content from the page and applies the gate (disable Save, flag Regenerate).
 *
 * Why this exists: the composed system prompt can drift from the dials/abilities it was composed from.
 * The numbers themselves never enter the prompt — the LLM composes prose — so the only way to know the
 * shown prompt is out of date is to track which controls changed since it was last composed.
 */

/** Classify a form control by its `name` — what role it plays in staleness. */
export function classifyField(name) {
  if (name === "systemPrompt") return "prompt";
  if (name === "descriptor" || name === "abilities" || (typeof name === "string" && name.startsWith("dial_"))) {
    return "composer-input";
  }
  return "other";
}

/**
 * Reduce the `stale` flag on an event. Events:
 *   { type: "field", name }   a control changed — staleness depends on which control
 *   { type: "regenerated" }   a fresh prompt was just composed in
 *
 *  - a composer input changed  -> stale (the shown prompt no longer reflects the inputs)
 *  - the prompt was hand-edited -> not stale (the owner has taken ownership of it)
 *  - a regenerate completed     -> not stale (freshly in sync)
 *  - anything else (name/model) -> unchanged (those don't feed the composed prompt)
 */
export function reduceStale(stale, event) {
  if (event.type === "regenerated") return false;
  if (event.type === "field") {
    const kind = classifyField(event.name);
    if (kind === "composer-input") return true;
    if (kind === "prompt") return false;
  }
  return stale;
}

/**
 * Whether the gate should engage — disable Save and flag Regenerate. Only meaningful once a prompt is
 * actually shown: a fresh create with an empty prompt isn't "stale", the server just composes on save.
 */
export function shouldGate(stale, promptHasContent) {
  return stale && promptHasContent;
}
