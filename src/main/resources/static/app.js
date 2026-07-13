/*
 * Scribble-friendly textarea auto-grow (ux brief §4): textareas rest generously, expand on focus, and
 * grow with content — plus one empty line of breathing room below the caret — up to a cap, then scroll.
 * Covers the thread composers and the persona form's descriptor/prompt fields. Pure progressive
 * enhancement — the forms work without it. Re-bound after every htmx swap so textareas injected into the
 * tree (inline composers) or swapped in place (a regenerated prompt) behave the same.
 */
import { submitLabel, toggleLabel, toggleCmd, effectiveMode, resolvePath } from "./composer-core.mjs";

(function () {
  var SELECTOR = ".composer textarea, .new-persona__descriptor, .new-persona__prompt";
  var MAX = 220;          // desktop growth cap (px)
  var MAX_MOBILE = 180;
  var isMobile = function () { return window.matchMedia("(max-width: 600px)").matches; };

  function grow(el) {
    var cap = isMobile() ? MAX_MOBILE : MAX;
    // One empty line of slack so the field is always content height + a blank line (a place to keep typing).
    var line = parseFloat(getComputedStyle(el).lineHeight) || 0;
    el.style.height = "auto";
    var target = el.scrollHeight + line;
    el.style.height = Math.min(target, cap) + "px";
    el.style.overflowY = target > cap ? "auto" : "hidden";
  }

  function bindOne(el) {
    if (!el.dataset.autogrow) {              // bind listeners once
      el.dataset.autogrow = "1";
      el.addEventListener("input", function (e) { grow(e.target); });
      el.addEventListener("focus", function (e) { grow(e.target); });
    }
    grow(el);                                // (re)size now — content may have just changed via a swap
  }

  function bind(root) {
    root = root || document;
    if (root.matches && root.matches(SELECTOR)) bindOne(root); // the swap target may BE a textarea
    var fields = root.querySelectorAll(SELECTOR);
    for (var i = 0; i < fields.length; i++) bindOne(fields[i]);
  }

  document.addEventListener("DOMContentLoaded", function () { bind(document); });
  // htmx swaps in new composers (inline replies, re-rendered nodes) and fresh prompt text (Regenerate) —
  // re-bind/re-grow the swapped element so it sizes to the new content.
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
    // Typing or deleting a resolvable @handle flips whether this submit will summon — keep the verb honest.
    applyMode(form);
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

  // The composer's current toggle mode, read straight off the form. Note is the default (the template
  // renders data-note-mode="1"); /ask drops the marker.
  function formMode(form) {
    return form.getAttribute("data-note-mode") === "1" ? "note" : "ask";
  }

  // Does the message tag a specific persona? Tagging always summons (ask), even from the note default —
  // naming someone is a deliberate request for their reply (see effectiveMode/resolvePath). A tag is
  // either a named persona chip selected, or a resolvable @handle typed in the message (matched against
  // the same handles/names the @mention menu offers, mirroring the server-side MentionParser).
  function tagsPersona(form) {
    var chips = form.querySelectorAll("[data-persona-chip] input");
    for (var i = 0; i < chips.length; i++) { if (chips[i].checked) return true; }
    var ta = form.querySelector("[data-composer-text]");
    if (!ta || ta.value.indexOf("@") < 0) return false;
    var handles = {};
    form.querySelectorAll("[data-mention-pick]").forEach(function (row) {
      var h = row.getAttribute("data-mention-handle");
      var n = row.getAttribute("data-mention-name");
      if (h) handles[h.toLowerCase()] = true;
      if (n) handles[n.toLowerCase()] = true;
    });
    var re = /(^|[^\w@])@([\w-]+)/g, m;
    while ((m = re.exec(ta.value)) !== null) { if (handles[m[2].toLowerCase()]) return true; }
    return false;
  }

  // Reflect the form's current mode (and any persona tag) onto its two buttons: the submit verb shows
  // what pressing it does RIGHT NOW — a tag forces "Ask ▸" even in the note default — and the footer
  // toggle offers the other mode. Pure-logic decisions come from composer-core; this is just the DOM
  // application. The endpoint swap is handled at submit time (htmx:configRequest below).
  function applyMode(form) {
    var mode = formMode(form);
    var submit = form.querySelector("button[type='submit']");
    if (submit) submit.textContent = submitLabel(effectiveMode(mode, tagsPersona(form)));
    var toggle = form.querySelector("[data-composer-shortcut]");
    if (toggle) {
      toggle.textContent = toggleLabel(mode);
      toggle.setAttribute("data-slash-cmd", toggleCmd(mode));
      toggle.setAttribute("data-composer-shortcut", toggleCmd(mode));
    }
  }

  // Enter note mode (the default): mark the form so configRequest retargets the POST at /note, and
  // relabel the buttons. A typed @tag / named chip still overrides this at submit time (resolvePath).
  function enterNoteMode(form) {
    form.setAttribute("data-note-mode", "1");
    applyMode(form);
  }

  // Leave note mode (/ask): drop the marker and relabel. The request URL is decided at submit time
  // (htmx:configRequest below) from data-note-mode, so there's no hx-post attribute to restore.
  function clearNoteMode(form) {
    form.removeAttribute("data-note-mode");
    applyMode(form);
  }

  // The note/ask switch, where it actually bites: rewrite the POST target to /note in note mode (the
  // default) UNLESS a persona is tagged, which keeps the summon on /generate. We do NOT mutate hx-post —
  // htmx caches the form's path on process, so a setAttribute is silently ignored and the request still
  // hits /generate. configRequest fires at request time, so rewriting evt.detail.path here always takes
  // effect. resolvePath folds the mode + tag decision (composer-core) into the final endpoint.
  document.body.addEventListener("htmx:configRequest", function (evt) {
    var elt = evt.detail && evt.detail.elt;
    var form = elt && elt.closest ? elt.closest("form[data-composer]") : null;
    if (form) evt.detail.path = resolvePath(evt.detail.path, formMode(form), tagsPersona(form));
  });

  // After a successful submit the composer resets — return it to the note default so the next message
  // is a silent note unless the owner opts back into summoning (/ask, or tagging a persona).
  document.body.addEventListener("reset", function (e) {
    var form = composerOf(e.target);
    if (form) { enterNoteMode(form); syncChips(form); }
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
      if (cmd === "note") {
        // Enter note mode: mark the form (the configRequest handler reads this to retarget the POST at
        // /note, so no LLM is summoned) and relabel the buttons. No hx-post mutation — see below.
        enterNoteMode(sForm);
      } else {
        // /ask explicitly returns to ask mode; /branch and /topic also leave note mode (their scope
        // only applies to a real summon). clearNoteMode restores the /generate endpoint + relabels.
        clearNoteMode(sForm);
      }
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
      applyMode(mForm); // a tag was added — the submit verb becomes "Ask ▸" even in the note default
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
    if (e.target.matches('.chip input[name="personaIds"]')) {
      var form = composerOf(e.target);
      syncChips(form, e.target);
      applyMode(form); // selecting/clearing a named chip changes whether the submit will ask
    }
  });
})();

/*
 * New-thread / new-member forms: Enter submits, Shift+Enter inserts a newline in the body/descriptor —
 * the same keyboard contract the composer gives its textarea above, so the text-entry surfaces feel
 * identical. The first <input> already submits on Enter natively, but the <textarea> would just insert a
 * newline; this extends the submit gesture to both fields. requestSubmit() fires a real submit event
 * (running validation + the action). Pure progressive enhancement — the submit button still works with
 * JS off.
 */
(function () {
  document.body.addEventListener("keydown", function (e) {
    var field = e.target.closest(
      "[data-new-thread] input, [data-new-thread] textarea, [data-new-persona] input, [data-new-persona] textarea"
    );
    if (!field || e.key !== "Enter" || e.shiftKey) return;
    e.preventDefault();
    var form = field.closest("form");
    if (typeof form.requestSubmit === "function") form.requestSubmit();
    else { var btn = form.querySelector("button[type='submit']"); if (btn) btn.click(); }
  });
})();

// Persona create/edit prompt-staleness guard lives in persona-form.js (+ persona-form-core.mjs, unit
// tested) — a pure-core/glue module pair like nav, loaded separately from layout.kte.
