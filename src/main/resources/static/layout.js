/*
 * layout.js — DOM glue for the collapsible side rails + "full screen" reading mode
 * (plan: collapsible-sidebars). Pure progressive enhancement, layered over the always-rendered
 * 3-column layout: with JS off both rails simply stay visible.
 *
 * The two booleans live as attributes on <html> (data-rail-left / data-rail-right = open|collapsed),
 * which the CSS keys off to hide a rail and reflow the grid. <html> is the single source of truth —
 * we read the current state off it, run the pure transition (layout-core), then write the attributes
 * back and persist to localStorage. The initial attributes are already set by the inline pre-paint
 * script in layout.kte (mirroring the theme toggle), so there's no flash on load.
 *
 * Event-delegated on the document so the same handler serves every control wherever it renders — the
 * header group AND the in-content full-screen button — with no per-element binding.
 */
import { toggleRail, toggleWide } from "./layout-core.mjs";

const KEY = { left: "rail-left", right: "rail-right" };

function readState() {
  const root = document.documentElement;
  return {
    left: root.getAttribute("data-rail-left") === "collapsed",
    right: root.getAttribute("data-rail-right") === "collapsed",
  };
}

function applyState(state) {
  const root = document.documentElement;
  root.setAttribute("data-rail-left", state.left ? "collapsed" : "open");
  root.setAttribute("data-rail-right", state.right ? "collapsed" : "open");
  try {
    localStorage.setItem(KEY.left, state.left ? "collapsed" : "open");
    localStorage.setItem(KEY.right, state.right ? "collapsed" : "open");
  } catch (e) {}
  reflectControls(state);
}

// Mirror the state onto the buttons so the header group + content button stay in sync. The per-rail
// buttons press when their side is collapsed; the wide button presses when both are.
function reflectControls(state) {
  document.querySelectorAll("[data-toggle-rail]").forEach(function (btn) {
    var side = btn.getAttribute("data-toggle-rail");
    btn.setAttribute("aria-pressed", String(!!state[side]));
  });
  var wide = !!(state.left && state.right);
  document.querySelectorAll("[data-toggle-wide]").forEach(function (btn) {
    btn.setAttribute("aria-pressed", String(wide));
  });
}

document.addEventListener("click", function (e) {
  var railBtn = e.target.closest("[data-toggle-rail]");
  if (railBtn) {
    applyState(toggleRail(readState(), railBtn.getAttribute("data-toggle-rail")));
    return;
  }
  if (e.target.closest("[data-toggle-wide]")) {
    applyState(toggleWide(readState()));
  }
});

// Reflect the persisted/initial state onto the controls once they exist in the DOM.
document.addEventListener("DOMContentLoaded", function () { reflectControls(readState()); });
