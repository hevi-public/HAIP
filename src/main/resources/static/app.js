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

/*
 * Theme switcher: each option persists a mode (dark | light | auto) and applies it. data-theme-mode
 * records the choice (drives the active-option underline, CSS-side); data-theme is the resolved palette
 * the styles key off. In "auto" the palette follows the OS and tracks it live (e.g. flips at sunset).
 * Initial state is already resolved by the inline <head> script in layout.kte to avoid a flash.
 */
(function () {
  var mql = matchMedia("(prefers-color-scheme: dark)");
  function resolve(mode) {
    return mode === "auto" ? (mql.matches ? "dark" : "light") : mode;
  }
  function apply(mode) {
    var root = document.documentElement;
    root.setAttribute("data-theme-mode", mode);
    root.setAttribute("data-theme", resolve(mode));
  }

  document.addEventListener("click", function (e) {
    var opt = e.target.closest("[data-set-theme]");
    if (!opt) return;
    var mode = opt.getAttribute("data-set-theme");
    apply(mode);
    try { localStorage.setItem("theme", mode); } catch (err) {}
  });

  // While in auto mode, follow OS changes live without a reload.
  mql.addEventListener("change", function () {
    var mode;
    try { mode = localStorage.getItem("theme"); } catch (err) {}
    if ((mode || "auto") === "auto") apply("auto");
  });
})();

/*
 * Composer affordances (ux brief §4): the persona chips, the slash command palette, and the @mention
 * summon menu. All pure progressive enhancement, layered over the plain form — with JS off the checkbox
 * chips still bind personaIds and the textarea still posts text. Everything is event-delegated on the
 * document, so composers htmx swaps into the tree (inline replies, re-rendered nodes) are wired with no
 * re-binding.
 */
(function () {
  function composerOf(el) { return el && el.closest(".composer"); }
  function show(el) { if (el) el.hidden = false; }
  function hide(el) { if (el) el.hidden = true; }
  function hidePalettes(form) {
    if (!form) return;
    hide(form.querySelector("[data-slash-menu]"));
    hide(form.querySelector("[data-mention-menu]"));
  }
  function chipInputFor(form, id) {
    var chips = form.querySelectorAll("[data-persona-chip]");
    for (var i = 0; i < chips.length; i++) {
      if (chips[i].getAttribute("data-persona-chip") === id) return chips[i].querySelector("input");
    }
    return null;
  }

  // Reflect every checkbox into .is-selected and enforce Anyone↔named exclusivity (named chips are
  // multi-select — naming several is a roomful summon, breadth follows who you tag). A summon always
  // needs a target, so if nothing is left checked we fall back to "Anyone". [changed] is the input the
  // user just toggled, if any.
  function syncChips(form, changed) {
    var anyone = form.querySelector("[data-anyone-chip] input");
    var personas = form.querySelectorAll("[data-persona-chip] input");
    if (changed) {
      if (changed === anyone && anyone.checked) {
        personas.forEach(function (p) { p.checked = false; });
      } else if (changed !== anyone && changed.checked) {
        if (anyone) anyone.checked = false;
      }
    }
    var anyChecked = !!(anyone && anyone.checked);
    personas.forEach(function (p) { if (p.checked) anyChecked = true; });
    if (!anyChecked && anyone) anyone.checked = true;
    form.querySelectorAll(".chip").forEach(function (chip) {
      var input = chip.querySelector("input");
      chip.classList.toggle("is-selected", !!(input && input.checked));
    });
  }

  // The "/" or "@" token the caret currently sits in — null if the caret isn't in one. The token must
  // start at the message start or after whitespace, so "and/or" and "foo@bar" don't trigger.
  function tokenAtCaret(ta) {
    var upto = ta.value.slice(0, ta.selectionStart);
    var m = /(^|\s)([/@])([\w-]*)$/.exec(upto);
    return m ? { kind: m[2], query: m[3].toLowerCase() } : null;
  }

  function filterMenu(menu, attrs, query) {
    var shown = 0;
    menu.querySelectorAll(".palette__row").forEach(function (row) {
      var match = !query || attrs.some(function (a) {
        var v = row.getAttribute(a);
        return v && v.toLowerCase().indexOf(query) === 0;
      });
      row.hidden = !match;
      if (match) shown++;
    });
    return shown;
  }

  // Replace the "/" or "@" token at the caret with [replacement], preserving the text after the caret.
  function replaceToken(ta, replacement) {
    var pos = ta.selectionStart;
    var upto = ta.value.slice(0, pos).replace(/(^|\s)[/@][\w-]*$/, "$1" + replacement);
    ta.value = upto + ta.value.slice(pos);
    ta.setSelectionRange(upto.length, upto.length);
    ta.focus();
  }

  // The slash or @mention popover currently open in [form], if any.
  function openPalette(form) {
    return form.querySelector("[data-slash-menu]:not([hidden]), [data-mention-menu]:not([hidden])");
  }
  function visibleRows(menu) {
    return Array.prototype.filter.call(menu.querySelectorAll(".palette__row"), function (r) { return !r.hidden; });
  }
  // Highlight the [idx]-th visible row (wrapping), so Enter has an obvious target. Defaults to the first.
  function highlight(menu, idx) {
    var rows = visibleRows(menu);
    rows.forEach(function (r) { r.classList.remove("is-active"); });
    if (!rows.length) return;
    var i = ((idx % rows.length) + rows.length) % rows.length;
    rows[i].classList.add("is-active");
    rows[i].scrollIntoView({ block: "nearest" });
  }
  function activeIndex(menu) {
    var rows = visibleRows(menu);
    for (var i = 0; i < rows.length; i++) { if (rows[i].classList.contains("is-active")) return i; }
    return -1;
  }

  // ---- typing: show/filter the right palette as the caret enters a / or @ token ----
  document.body.addEventListener("input", function (e) {
    if (!e.target.matches("[data-composer-text]")) return;
    var form = composerOf(e.target);
    var slash = form.querySelector("[data-slash-menu]");
    var mention = form.querySelector("[data-mention-menu]");
    var tok = tokenAtCaret(e.target);
    hide(slash); hide(mention);
    if (!tok) return;
    // Re-highlight the first row each keystroke so Enter completes the top match (the user's ask).
    if (tok.kind === "/" && slash && filterMenu(slash, ["data-slash-cmd"], tok.query)) { show(slash); highlight(slash, 0); }
    if (tok.kind === "@" && mention && filterMenu(mention, ["data-mention-handle", "data-mention-name"], tok.query)) { show(mention); highlight(mention, 0); }
  });

  // ---- keyboard: navigate / complete / dismiss the open palette ----
  document.body.addEventListener("keydown", function (e) {
    if (!e.target.matches("[data-composer-text]")) return;
    var form = composerOf(e.target);
    if (e.key === "Escape") {
      // Tiered Escape, reconciled with the thread-nav branch (HANDOVER.md): if a palette is open,
      // dismiss it and STOP the event so nav.js doesn't also close the composer — 1st Esc dismisses the
      // palette and keeps focus in the field. With no palette open we let Escape bubble to thread-nav, so
      // a 2nd Esc exits the composer back to the reading cursor.
      if (openPalette(form)) { hidePalettes(form); e.stopPropagation(); }
      return;
    }
    var menu = openPalette(form);
    if (!menu) {
      // No palette open: Enter submits the form, Shift+Enter inserts a newline (default textarea
      // behavior). requestSubmit() fires a real submit event so htmx intercepts and posts it.
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        if (typeof form.requestSubmit === "function") form.requestSubmit();
        else { var btn = form.querySelector("button[type='submit']"); if (btn) btn.click(); }
      }
      return;
    }
    if (e.key === "ArrowDown") { e.preventDefault(); highlight(menu, activeIndex(menu) + 1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); highlight(menu, activeIndex(menu) - 1); }
    else if (e.key === "Enter") {
      var rows = visibleRows(menu);
      if (!rows.length) return;
      e.preventDefault();             // complete the mention/command instead of a newline
      rows[Math.max(0, activeIndex(menu))].dispatchEvent(new MouseEvent("click", { bubbles: true }));
    }
  });

  // ---- clicks: chips, slash commands, mention picks ----
  document.body.addEventListener("click", function (e) {
    var slashCmd = e.target.closest("[data-slash-cmd]");
    if (slashCmd) {
      e.preventDefault();
      var sForm = composerOf(slashCmd);
      var cmd = slashCmd.getAttribute("data-slash-cmd");
      var ta = sForm.querySelector("[data-composer-text]");
      if (ta) replaceToken(ta, ""); // drop the "/cmd" the user was typing
      if (cmd === "branch" || cmd === "topic") {
        var val = cmd === "branch" ? "BRANCH_ONLY" : "WHOLE_THREAD";
        // The generation scope (what the persona reads) is hidden; the only on-screen context control is
        // the "looking at" select. Drive BOTH so the command is legible — the select reflects the change
        // and the dispatcher's scope aligns with what the persona will read.
        var scopeInput = sForm.querySelector('input[name="scope"]');
        if (scopeInput) scopeInput.value = val;
        sForm.setAttribute("data-scope", val);
        var routingSel = sForm.querySelector('select[name="routingScope"]');
        if (routingSel) routingSel.value = val;
      }
      hide(sForm.querySelector("[data-slash-menu]"));
      return;
    }
    var pick = e.target.closest("[data-mention-pick]");
    if (pick) {
      e.preventDefault();
      var mForm = composerOf(pick);
      var ta2 = mForm.querySelector("[data-composer-text]");
      if (ta2) replaceToken(ta2, "@" + pick.getAttribute("data-mention-handle") + " ");
      var chip = chipInputFor(mForm, pick.getAttribute("data-mention-pick"));
      if (chip) { chip.checked = true; syncChips(mForm, chip); }
      hide(mForm.querySelector("[data-mention-menu]"));
      return;
    }
    // a click anywhere else (outside a palette / the editor) dismisses any open palette
    if (!e.target.closest(".palette") && !e.target.closest("[data-composer-text]")) {
      document.querySelectorAll(".composer").forEach(hidePalettes);
    }
  });

  // ---- chip checkbox toggled directly (keyboard / no-JS-style click on the label) ----
  document.body.addEventListener("change", function (e) {
    if (e.target.matches('.chip input[name="personaIds"]')) syncChips(composerOf(e.target), e.target);
  });
})();

/*
 * New-thread form (home page): Enter submits, Shift+Enter inserts a newline in the body — the same
 * keyboard contract the composer gives its textarea above, so the two text-entry surfaces feel
 * identical. The title <input> already submits on Enter natively, but the body <textarea> would just
 * insert a newline; this extends the submit gesture to both fields. requestSubmit() fires a real submit
 * event (running validation + the action). Pure progressive enhancement — the Create button still works
 * with JS off.
 */
(function () {
  document.body.addEventListener("keydown", function (e) {
    var field = e.target.closest("[data-new-thread] input, [data-new-thread] textarea");
    if (!field || e.key !== "Enter" || e.shiftKey) return;
    e.preventDefault();
    var form = field.closest("form");
    if (typeof form.requestSubmit === "function") form.requestSubmit();
    else { var btn = form.querySelector("button[type='submit']"); if (btn) btn.click(); }
  });
})();

// Persona create/edit prompt-staleness guard lives in persona-form.js (+ persona-form-core.mjs, unit
// tested) — a pure-core/glue module pair like nav, loaded separately from layout.kte.
