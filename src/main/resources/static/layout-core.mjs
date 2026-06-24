/*
 * layout-core.mjs — pure state logic for the collapsible sidebars / "full screen" reading mode
 * (plan: collapsible-sidebars). No DOM, no storage — just the state transitions, so they can be
 * unit-tested in isolation (see layout-core.test.mjs). The glue that reads <html>'s attributes,
 * persists to localStorage and binds clicks lives in layout.js.
 *
 * State is two independent booleans — whether each side rail is collapsed:
 *     { left: boolean, right: boolean }   // true = collapsed (hidden), false = open
 *
 * "Wide" / full-screen is the derived case where BOTH are collapsed, so the main column gets the
 * whole browser width. It isn't a third flag — the wide button just flips both at once.
 */

// The side rails we can collapse. Anything else is ignored (toggleRail is a no-op).
const SIDES = ["left", "right"];

// Are we in full-width mode? True only when both rails are collapsed.
export function isWide(state) {
  return !!(state && state.left && state.right);
}

// Toggle one rail, leaving the other untouched. Unknown sides return the state unchanged.
export function toggleRail(state, side) {
  const s = { left: !!(state && state.left), right: !!(state && state.right) };
  if (!SIDES.includes(side)) return s;
  s[side] = !s[side];
  return s;
}

// The full-screen button: if already wide (both collapsed) restore both, otherwise collapse both.
export function toggleWide(state) {
  const open = !isWide(state);          // currently not wide → collapse both
  return { left: open, right: open };
}
