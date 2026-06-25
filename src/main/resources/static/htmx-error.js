/*
 * htmx-error.js — DOM glue for honest htmx failure handling (T1.4). The decisions (notice copy,
 * which control to re-enable) live in htmx-error-core.mjs and are unit-tested; this file is the
 * (manually-verified) wiring: it listens for the two htmx error events globally, re-enables the
 * control htmx left disabled, and surfaces a non-blocking toast.
 *
 * Without this, a failed poll / Regenerate / +1 leaves the `hx-disabled-elt` button permanently
 * disabled (the re-enabling swap never arrives on an error) and the `hx-indicator` spinner spinning,
 * with no feedback. The server-side fragment (HtmxErrorAdvice) handles the swap-target corruption; this
 * handles the stuck control + silence. Loaded as an ES module from layout.kte like the other glue.
 */
import { noticeFor, shouldReEnable, RESPONSE_ERROR, SEND_ERROR } from "./htmx-error-core.mjs";

(function () {
  // Re-enable [el] if it's a control htmx left disabled for the failed request.
  function reEnable(el) {
    if (shouldReEnable(el)) {
      el.disabled = false;
      // htmx also adds [aria-disabled]/removes it via the swap; clear it so AT sees the live control.
      el.removeAttribute("aria-disabled");
    }
  }

  // Re-enable the triggering element AND anything its hx-disabled-elt pointed at (it's the swap that
  // would have re-enabled them, and on an error the swap never runs). "this" / "closest …" / a plain
  // selector are the shapes htmx accepts; we resolve them relative to the triggering element.
  function reEnableDisabledElts(trigger) {
    if (!trigger) return;
    reEnable(trigger);
    var spec = trigger.getAttribute && trigger.getAttribute("hx-disabled-elt");
    if (!spec) return;
    spec.split(",").forEach(function (raw) {
      var sel = raw.trim();
      if (!sel || sel === "this") return; // "this" is the trigger, already handled above
      var targets = sel.indexOf("closest ") === 0
        ? [trigger.closest(sel.slice("closest ".length).trim())]
        : Array.prototype.slice.call(document.querySelectorAll(sel));
      targets.forEach(reEnable);
    });
  }

  // A self-dismissing toast in a shared live region — non-blocking, never steals focus. Created lazily.
  function toast(message) {
    var region = document.querySelector("[data-error-toasts]");
    if (!region) {
      region = document.createElement("div");
      region.setAttribute("data-error-toasts", "");
      region.className = "error-toasts";
      region.setAttribute("aria-live", "assertive");
      region.setAttribute("role", "status");
      document.body.appendChild(region);
    }
    var note = document.createElement("div");
    note.className = "error-toast";
    note.setAttribute("data-error-toast", "");
    note.textContent = message;
    region.appendChild(note);
    setTimeout(function () { note.remove(); }, 6000);
  }

  function onError(e) {
    var detail = e.detail || {};
    var trigger = detail.elt || e.target;
    reEnableDisabledElts(trigger);
    var status = detail.xhr ? detail.xhr.status : null;
    toast(noticeFor(e.type, status));
  }

  document.body.addEventListener(RESPONSE_ERROR, onError);
  document.body.addEventListener(SEND_ERROR, onError);
})();
