/*
 * persona-form.js — DOM glue for the persona create/edit form's prompt/dials divergence guard
 * (see plan_docs/persona-prompt-edit-ux.md). All the LOGIC lives in persona-form-core.mjs and is
 * unit-tested; this file is the (manually verified) glue.
 *
 * Pure progressive enhancement: changing a composer input (a dial / abilities / descriptor) marks the
 * shown prompt STALE — Save is disabled and Regenerate is flagged (.is-needed → a ⚠ highlight). A
 * Regenerate (htmx swaps fresh text into the prompt) or a manual prompt edit clears it. With JS off the
 * server still resyncs a stale prompt on save, so this only nudges; it never gates correctness.
 * Event-delegated on the document, so it covers any persona form on the page with no per-element binding.
 */
import { classifyField, reduceStale, shouldGate } from "./persona-form-core.mjs";

const FORM = "[data-persona-form]";
const staleByForm = new WeakMap(); // form element -> boolean (its prompt is stale)

function isStale(form) {
  return staleByForm.get(form) === true;
}

function apply(form, stale) {
  staleByForm.set(form, stale);
  const prompt = form.querySelector("[data-prompt-field]");
  const gate = shouldGate(stale, !!(prompt && prompt.value.trim().length));
  form.classList.toggle("is-prompt-stale", gate);
  const save = form.querySelector('button[type="submit"]');
  if (save) save.disabled = gate;
  const regen = form.querySelector("[data-preview-prompt]");
  if (regen) regen.classList.toggle("is-needed", gate);
}

function onField(e) {
  const form = e.target.closest && e.target.closest(FORM);
  if (!form) return;
  const name = (e.target.getAttribute && e.target.getAttribute("name")) || "";
  if (classifyField(name) === "other") return; // model/name don't feed the prompt — ignore
  apply(form, reduceStale(isStale(form), { type: "field", name }));
}

document.addEventListener("input", onField);
document.addEventListener("change", onField);
// A successful Regenerate swaps fresh text into the prompt field → in sync again.
document.addEventListener("htmx:afterSwap", function (e) {
  const form = e.target.closest && e.target.closest(FORM);
  if (form && e.target.matches && e.target.matches("[data-prompt-field]")) {
    apply(form, reduceStale(isStale(form), { type: "regenerated" }));
  }
});
