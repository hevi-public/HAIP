/*
 * Scribble-friendly composer auto-grow (ux brief §4): textareas rest generously, expand on focus, and
 * grow with content up to a cap, then scroll. Pure progressive enhancement — the form works without it.
 * Re-bound after every htmx swap so composers injected into the tree (beforeend) behave the same.
 */
(function () {
  var MAX = 220;          // desktop growth cap (px)
  var MAX_MOBILE = 180;
  var isMobile = function () { return window.matchMedia("(max-width: 600px)").matches; };

  function grow(el) {
    var cap = isMobile() ? MAX_MOBILE : MAX;
    el.style.height = "auto";
    el.style.height = Math.min(el.scrollHeight, cap) + "px";
    el.style.overflowY = el.scrollHeight > cap ? "auto" : "hidden";
  }

  function bind(root) {
    var fields = (root || document).querySelectorAll(".composer textarea");
    for (var i = 0; i < fields.length; i++) {
      var el = fields[i];
      if (el.dataset.autogrow) continue;     // bind once
      el.dataset.autogrow = "1";
      el.addEventListener("input", function (e) { grow(e.target); });
      el.addEventListener("focus", function (e) { grow(e.target); });
      grow(el);
    }
  }

  document.addEventListener("DOMContentLoaded", function () { bind(document); });
  // htmx swaps in new composers (inline replies, re-rendered nodes) — re-bind the swapped subtree.
  document.body.addEventListener("htmx:afterSwap", function (e) { bind(e.target); });
})();
