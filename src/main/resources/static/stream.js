/*
 * Live generation streaming (plan: AG-UI hybrid SSR + SSE). A drafting reply node self-polls `every 1s`
 * (replyNode.kte) AND, when this module is active, opens an EventSource to /replies/{id}/stream so the
 * reply TEXT appears token-by-token while it generates. On the terminal event we close the stream and let
 * the server-rendered fragment swap in (markdown, highlighting, voting, revisions) via the existing poll
 * endpoint — so the DB + server rendering stay the source of truth and the client never renders markdown.
 *
 * Pure progressive enhancement: the `every 1s` htmx poll is the backbone and the fallback. If EventSource
 * is unsupported, errors, or the run already settled, the node still settles via the poll. Events are the
 * AG-UI vocabulary (see AguiWire): RUN_STARTED / TEXT_MESSAGE_CONTENT / TOOL_CALL_START|END /
 * RUN_FINISHED / RUN_ERROR, each arriving as a named SSE event with a JSON data payload.
 */
(function () {
  if (typeof EventSource === "undefined") return; // no SSE → the htmx poll handles everything

  function bodyEl(article) {
    return article.querySelector(":scope > .body");
  }

  // A small status line for tool-call activity ("calling WebFetch…"); created lazily, no CSS required.
  function statusEl(article) {
    var el = article.querySelector(":scope > .reply__stream-status");
    if (!el) {
      el = document.createElement("div");
      el.className = "reply__stream-status";
      el.setAttribute("data-stream-status", "");
      el.setAttribute("aria-live", "polite");
      var body = bodyEl(article);
      if (body) body.insertAdjacentElement("afterend", el);
      else article.appendChild(el);
    }
    return el;
  }

  function appendText(article, delta) {
    var body = bodyEl(article);
    if (!body) return;
    article.setAttribute("data-streaming", "1"); // hook for optional styling; harmless if unstyled
    body.textContent += delta; // raw text — server re-renders the markdown on settle; never inject HTML
  }

  // Settle: stop streaming and pull the authoritative server-rendered fragment in at once (the poll would
  // do this within a second anyway; this just removes the lag). Guarded so a redundant fetch is avoided
  // once the node is no longer drafting.
  function settle(article, source) {
    source.close();
    var id = article.getAttribute("data-reply-id");
    var current = document.getElementById("reply-" + id);
    if (!current || current.getAttribute("data-state") !== "drafting") return;
    if (window.htmx) {
      window.htmx.ajax("GET", "/replies/" + id, { target: "#reply-" + id, swap: "outerHTML" });
    }
    // else: no htmx (shouldn't happen) — the 1s poll still settles it.
  }

  function attach(article) {
    if (article.dataset.streamBound) return; // bind once per node
    var id = article.getAttribute("data-reply-id");
    if (!id) return;
    article.dataset.streamBound = "1";

    var es = new EventSource("/replies/" + id + "/stream");
    article._streamSource = es; // so htmx cleanup can close it when the 1s poll swaps this node away

    es.addEventListener("TEXT_MESSAGE_CONTENT", function (e) {
      var delta = JSON.parse(e.data).delta;
      if (delta) appendText(article, delta);
    });
    es.addEventListener("TOOL_CALL_START", function (e) {
      var name = JSON.parse(e.data).toolCallName || "a tool";
      statusEl(article).textContent = "calling " + name + "…";
    });
    es.addEventListener("TOOL_CALL_END", function () {
      var el = article.querySelector(":scope > .reply__stream-status");
      if (el) el.textContent = "";
    });
    es.addEventListener("RUN_FINISHED", function () { settle(article, es); });
    es.addEventListener("RUN_ERROR", function () { settle(article, es); });

    // Any connection error (incl. the server completing the stream for an already-settled/unknown run):
    // close and lean on the htmx poll. EventSource would otherwise auto-reconnect into a loop.
    es.onerror = function () { es.close(); };
  }

  function bind(root) {
    if (!root || !root.querySelectorAll) return;
    if (root.matches && root.matches('article.reply[data-state="drafting"]')) attach(root);
    root.querySelectorAll('article.reply[data-state="drafting"]').forEach(attach);
  }

  document.addEventListener("DOMContentLoaded", function () { bind(document); });
  // htmx swaps in fresh drafting nodes (a summon's reply list, an async-summon room poll) — attach to them
  // too, mirroring app.js's re-bind on swap.
  document.body.addEventListener("htmx:afterSwap", function (e) { bind(e.target); });
  // The drafting node self-polls `every 1s` with hx-swap="outerHTML", so htmx discards the whole <article>
  // (and opens a fresh one) each second. htmx fires htmx:beforeCleanupElement on the element it removes;
  // close that element's EventSource here. Without this the orphaned stream stays open while attach() binds
  // a new one to the fresh node, leaking ~1 SSE connection/second until the browser's ~6-per-host HTTP/1.1
  // pool is exhausted and both the stream and the poll fallback stall. e.target is the element being cleaned
  // (the event bubbles), and only streaming articles carry _streamSource, so descendant cleanups are no-ops.
  document.body.addEventListener("htmx:beforeCleanupElement", function (e) {
    var es = e.target && e.target._streamSource;
    if (es) { es.close(); e.target._streamSource = null; }
  });
})();
