# Comment quotes — a citation graph over the comment tree

Design note for **quotes**: select text in a comment, "Quote" it into a reply, and have that quote
become a **traversable, bidirectional link** between the two comments. Many quotes of the same passage
coalesce, so a heavily-cited passage becomes a hub you can fan out from. It gives the forum a light
"semantic web" / backlink layer on top of the existing reply tree.

Relates to §3 (content & data model), §5 (context scoping), and §8 (node operations) of
`ai-forum-requirements.md`. It generalises the existing **"in reply to" anchor** (see
[markdown-rendering.md](markdown-rendering.md) and `ParentRef`), which is already a literal, truncated,
linked snapshot of *one* comment (the tree-parent). Quotes are the same idea, freed from the tree edge:
any span of any comment, in either direction.

---

## 1. The shape of the feature

Two directions over one relation:

- **Forward** (quoter → quotee): a comment that contains a quote shows *where it came from* — a small
  "↗ <author>" anchor that jumps to the source comment. This is the in-reply-to affordance, generalised
  to arbitrary spans and to N sources.
- **Backward** (quotee → quoters): a comment whose text has been quoted shows *who quoted it*. The cited
  passage becomes a marker; when several comments quote the same passage, they **coalesce into one
  marker**; hovering reveals a selector of all the quoters; clicking a quoter navigates to it.

Authoring:

- **Toolbar quote** — select text in a comment and a small floating **❝ Quote** toolbar appears above the
  selection (Medium / Google Docs style). The selection is inserted as a markdown blockquote into a
  composer, and a quote **edge** is recorded linking the new reply to the source. Destination is
  **smart**: if a composer is already open/focused, the quote drops into it (you're assembling one reply
  that cites several sources); otherwise a new inline reply opens as a **child of the quoted comment**,
  pre-filled.

  > **Why a selection toolbar, not a right-click menu.** The first cut hijacked the `contextmenu` event,
  > but a web page cannot *extend* the browser's native menu (the `<menu type="context">` API was removed
  > from the spec and ships in no current browser), so right-click could only **replace** it — costing the
  > user Copy / Search / Inspect / spellcheck. A floating toolbar triggered by an ordinary left-selection
  > leaves the native menu untouched and is the established pattern for selection actions. Future actions
  > (e.g. "copy link to this passage") slot into the same toolbar. Touch and keyboard paths (an action-bar
  > button, a `nav.js` shortcut) are options for later — the toolbar is mouse-first.
- **Manual quote** — a hand-typed `> blockquote`. Renders as an ordinary blockquote. It carries **no
  edge** (text alone can't tell us which comment it came from), so it has no link — until/unless it is
  explicitly associated with a source (a later affordance). See §5 on why we don't store a "type".

---

## 2. Data model — quote edges

A new table (migration **V18**, after V17). Edges are directed: `src` quotes `target`.

```sql
CREATE TABLE comment_quote (
    id                TEXT PRIMARY KEY,
    thread_id         TEXT NOT NULL,      -- denormalised: one batch read of a thread's edges
    src_comment_id    TEXT NOT NULL,      -- the QUOTING comment (carries the blockquote)
    target_comment_id TEXT NOT NULL,      -- the QUOTED comment (the source of the text)
    quoted_text       TEXT NOT NULL,      -- verbatim SNAPSHOT of the quoted span (see §4)
    created_at        TEXT NOT NULL,
    FOREIGN KEY (src_comment_id)    REFERENCES comment(id) ON DELETE CASCADE,
    FOREIGN KEY (target_comment_id) REFERENCES comment(id) ON DELETE CASCADE
);
CREATE INDEX idx_comment_quote_src    ON comment_quote(src_comment_id);
CREATE INDEX idx_comment_quote_target ON comment_quote(target_comment_id);
```

- **`thread_id`** lets `ReplyTreeAssembler` read all of a thread's edges in one query and fold them into
  the views, the same way it batch-reads votes / revisions / attachments today.
- **FK `ON DELETE CASCADE`** on both ends: deleting a comment (and `deleteSubtree` cascades the tree)
  removes any edge it participates in, as quoter or quotee. ⚠ Tier-1 cleanup is manual-`DELETE`-list by
  convention and FK enforcement is on — `comment_quote` **must** be added to every DB-reset / cleanup
  list (acceptance `@Before`, tier-1 `@BeforeEach`/`@AfterEach`, `CommentRepository.deleteSubtree` /
  `deleteByThread`) and deleted **before** `comment`. (This is the FK-isolation trap that has cost red
  runs before — see the project memory on tier-1 isolation.)
- `MigrationPipelineTest` MAX version bumps 17 → 18.

`comment_quote` mirrors the `comment_revision` naming convention.

---

## 3. Rendering

### Forward ref (the slice)

The quoted text is already visible in the quoter's body as a `> blockquote` (markdown renders it). On
top of that, the quoter shows a **quotes strip** — sibling of the in-reply-to anchor — listing each
source:

```
↗ <target author>: <short snippet of the quoted text>     → href="#reply-<targetId>"
```

with a stable `data-quote-source="<targetId>"` hook (the acceptance probe reads the anchor by it). The
strip is driven by a new `ReplyView.quotes: List<QuoteRef>` (default empty), populated in the assembler.
`QuoteRef(targetId, targetAuthor, snippet)` reuses `Snippet.oneLine` for the preview, exactly like
`ParentRef`.

Decoration of the *actual blockquote* (turning the inline `<blockquote>` into the link, rather than a
separate strip) is deferred — it needs the same text-matching machinery the backward direction builds,
so it lands with §6.

### Backward refs (deferred — §6)

A comment whose `id` appears as a `target_comment_id` of N edges. Best-effort re-find each `quoted_text`
in the rendered body and wrap it in a `<mark class="quoted">` that links to / fans out to the quoters.
Coalescing + the hover selector are the bulk of the work; see §6.

---

## 4. Anchoring — snapshot now, robust selector later

A quote points at a **span of text** inside a comment whose body is **versioned and editable** (V14
revisions; `comment.body` is the denormalised body of the *selected* revision). The quoted span can be
edited, regenerated, or deleted out from under the link. `versioned-conversation-branches.md` already
flags this exact "body changes, the link doesn't" fragility for the structural in-reply-to quote.

**Decision (chosen): snapshot text + comment-level link.** Store the quoted text *verbatim* on the edge
and make the link target the source **comment** (not a live character range). This matches what the
in-reply-to anchor already does (a literal stored snapshot) and the attachment-caption snapshot pattern.
Consequences:

- The **forward** link always works — it targets a comment id, which is stable.
- The **backward** highlight is **best-effort**: re-find `quoted_text` in the current body and mark it.
  If the source was edited and the exact text is gone, we simply don't paint the inline mark — but the
  edge still counts, so the quotee can still surface "quoted by N" at the comment level (e.g. a footer /
  rail entry) and the quoter's forward link is unaffected. The link **degrades, never breaks**.
- Cheap, no new failure modes, no offset bookkeeping.

**Alternative (documented, not built): robust text-quote selector.** Store, alongside the exact text, a
short **prefix** and **suffix** of surrounding context (the W3C Web Annotation `TextQuoteSelector`
shape), and optionally a fallback character position hint. On render, re-anchor by searching for
`prefix + exact + suffix`, then `exact`, then a fuzzy match — so the inline mark survives edits, reflow,
and duplicate-text ambiguity far better. This is the natural upgrade if best-effort highlighting proves
too lossy in practice. It is a **pure additive change to the edge row + a pure re-anchoring function**
(Tier-0 testable) — the table grows two nullable columns (`quote_prefix`, `quote_suffix`), the
forward/edge semantics are unchanged, and the smart-destination UX is unchanged. We deliberately do
**not** build it now (YAGNI): the snapshot already gives a working, resilient link, and we'd be guessing
at the re-anchoring heuristics before seeing real edit patterns.

**Rejected: character offsets.** Storing start/end offsets into the body is trivial to highlight but
shatters on any edit/regenerate of the source — worst fit given V14.

> A subtlety for the robust version: selection happens in the **rendered DOM**, but bodies are authored
> and stored as **markdown**. Offsets into rendered HTML ≠ offsets into markdown source. The snapshot
> approach sidesteps this entirely (we store the selected *text*, re-find it in the *rendered* body).
> The selector approach would also operate on rendered text (prefix/exact/suffix are all DOM-text), so
> it never needs to map back to markdown offsets either.

---

## 5. On "do we need a quote type?" — and folding in manual / persona quotes

No type discriminator. The meaningful distinction is **linked vs. plain blockquote** — not manual-vs-menu.
A toolbar quote creates a stored edge by construction; a `> blockquote` (hand-typed, or written by an LLM
persona) carries none on its own. We don't persist a "manual"/"menu" flag — everything is derived from
edge presence.

**But manual/persona blockquotes ARE now folded into the graph** (added after the toolbar shipped — an
LLM persona quoting a passage was invisible to the backlinks, which is wrong; this was the original "manual
quotes are a quote type" ask). `QuoteScanner` (Tier-0, `com.aiforum.markdown`) derives edges from
blockquotes **at render time** in the assembler:

- For each `> ` blockquote passage in a comment, find the **unique** other comment whose **prose** (its
  body with blockquote lines stripped) contains that text, and link to it. Matching against *prose* is the
  load-bearing trick: the same passage appears as a `> ` block in every *quoter* and as prose only in the
  *original*, so it resolves to the source, not to sibling quoters.
- Zero matches (external/paraphrased/OP quotes), several matches (ambiguous), or a too-short passage
  (`< MIN_LEN`) are left **unlinked** — best-effort, consistent with snapshot anchoring (§4).
- Derived edges are **de-duped against stored** ones (a toolbar quote's body also carries the inserted
  blockquote, which must not count twice) and merged before grouping, so a typed quote and a toolbar quote
  of the same passage **coalesce** into one backlink group.

**Decision: derive at render (chosen)** — no schema, works **retroactively** on every existing
persona/typed blockquote, and naturally covers both directions (the derived edge feeds the forward strip
*and* the backward backlinks). **Alternative (documented, not built): persist derived edges.** Run the
same matching at post time (hook the generation settle path for persona replies) + a one-time backfill,
writing real `comment_quote` rows. That unifies storage and makes the graph queryable (e.g. an admin
view) without re-scanning per render, at the cost of a write-path hook, a migration/backfill, and
staleness when a source body is later edited (the derive-at-render pass always reflects current text). We
chose derive-at-render for the PoC; persisting is the upgrade if the per-render scan ever costs too much
or an offline graph query is needed.

---

## 6. Backward refs, coalescing, the selector cone — BUILT (second slice)

The richer half of the original idea. Built as a follow-up slice (see §9) after the forward half proved
the model; **no new schema** — just `QuoteRepository.byTarget` mirroring `bySource`.

- **Backlinks.** ✅ For each comment, the edges where it is the `target`, surfaced as a server-rendered
  "quoted by N" block (`.reply__quoted-by`) — the no-JS fallback *and* the data the client promotes. It
  survives edits even when the inline span can't be re-found (the comment-level entry stays visible).
- **Inline marker + coalescing.** ✅ `quote-backlinks.js` best-effort re-finds each `quoted_text` in the
  rendered body (whitespace-tolerant, `quote-backlinks-core.matchPassage`) and wraps it in
  `<mark class="quoted">` client-side (the server body HTML / XSS firewall stays untouched). Coalescing is
  **per-exact-span** (the chosen granularity): identical `quoted_text` collapses to one mark + one cone;
  distinct passages stay separate marks. *(Deferred refinement: per-overlapping-region union marks — the
  hardest variant; revisit only if partial-overlap quotes prove common. Per-comment was rejected as too
  coarse — it loses "the relevant section becomes a link".)*
- **Selector cone.** ✅ Hovering/focusing a mark reveals a `.quote-cone` popover listing the quoters
  (author + snippet + jump link), built from the SSR block; clicking a quoter navigates (single-quoter
  click jumps directly). Implemented as a simple list popover in the slash/@mention palette idiom (not a
  literal radial fan).
- **Graceful degradation.** A passage the client can't re-find stays in the visible SSR fallback list, so
  a backlink is never silently lost; if every passage is located inline, the fallback block hides.

**Still deferred:** **decorated forward blockquote** (now that text-matching exists, the *forward*
`<blockquote>` could also carry the source link inline, not just the strip); an **admin / graph view**
(most-quoted comments, dangling edges — all data is in `comment_quote`); the robust `TextQuoteSelector`
anchoring (§4); and quoting the **OP**.

---

## 7. The slice we build now (forward quoting)

A complete vertical slice that establishes the edge model and the forward direction end-to-end.

**Server**
1. `V18__comment_quote.sql` + `QuoteRepository` (insert; `bySource(threadId)` batch read). Bump
   `MigrationPipelineTest`. Add `comment_quote` to every DB-reset/cleanup list.
2. `QuoteSpec(targetId, text)` (input) and `QuoteRef(targetId, targetAuthor, snippet)` (view).
3. Carry pending quotes over the wire as a single JSON field **`quotesJson`** on `GenerateRequest` and
   as a `/note` request param — one representation for both the browser form path and the JSON
   acceptance path, avoiding fragile nested-list form binding. Parsed server-side with Jackson.
4. Record edges in `GenerationController` once the owner's node id is known: the `/generate` paths return
   **owner-rooted** views (`owner.toReplyView(children = drafts)`), so the owner node id is recoverable;
   the `/note` and image-multipart paths already hold the node directly. A quote with an unknown /
   cross-thread target is ignored (defensive); duplicate `(target, text)` pairs are de-duped.
5. Enrich `ReplyView.quotes` in `ReplyTreeAssembler.assemble` (batch `bySource`, resolve target author +
   snippet from the in-memory `byId`).
6. Render the forward quotes strip in `replyNode.kte` with `data-quote-source` hooks.

**Client** (progressive enhancement; with JS off, nothing breaks — manual `> ` blockquotes still post)
7. `quote-core.mjs` (pure, unit-tested): `toBlockquote(text)` (multi-line → each line `> `-prefixed,
   trimmed) and serialise/parse of the pending-quotes payload.
8. `quote.js` (DOM glue, loaded in `layout.kte`): on a settled selection (`mouseup` / keyboard select)
   inside a comment body (`.reply .body` — **not** the OP `.thread__body`, see below), float a **❝ Quote**
   toolbar above the selection rect; hide it when the selection collapses (`selectionchange`), on
   `Escape`, and reposition it on scroll/resize. The native `contextmenu` is left untouched. Choosing
   Quote resolves the **smart destination** (active composer else open the source's inline reply
   `<details>` + focus it) → inserts the blockquote → accumulates the pending quote against that form →
   serialises to `quotesJson` at `htmx:configRequest` (the same seam app.js uses for note/ask path
   rewriting). The toolbar button `preventDefault`s its `mousedown` so taking the click doesn't collapse
   the selection.
9. CSS for the selection toolbar (`.quote-toolbar`) + the forward strip (extends the in-reply-to styling).

**Tests** (tiered, per the project's BDD discipline)
10. Tier-0: `quote-core` JS unit test (blockquote building, payload round-trip).
11. Tier-1: `QuoteRepositoryTest` (insert + `bySource`; isolation cleanup includes `comment_quote`).
12. Acceptance: `comment_quotes.feature` — POST a reply with `quotesJson` → an edge is created → the
    rendered node carries the forward `data-quote-source` anchor pointing at `#reply-<target>`. The
    browser-only interaction (selection, right-click menu, smart destination) is verified via the
    preview tooling, not the HTTP suite — the same split keyboard-nav uses.

**Out of scope for this (first) slice:** everything in §6 — now built as the **second slice** (see §9).
The robust text-selector anchoring (§4), the decorated forward blockquote, an admin graph, and quoting
the OP remain deferred.

**Quoting the OP is deferred.** The opening post is the *thread* row (`thread.body`, id == threadId),
not a `comment` row (see `haip-op-node-model`), so there is no comment id for an edge to target — the FK
(`target_comment_id REFERENCES comment(id)`) and the same-thread validation would drop it. The context
menu is therefore scoped to `.reply .body`. Quoting the OP would mean either materialising the OP as an
addressable node or a special `target_thread_id` edge variant; left for later.

---

## 8. Interactions with in-flight / existing work

- **In-reply-to anchor (`ParentRef`).** The forward strip is its sibling and shares its styling. We keep
  them distinct: in-reply-to is *structural* (the tree edge, auto-derived), quotes are *content
  cross-links* (explicit, can cross branches). A future cleanup could unify their visual language.
- **PR #88 (live token streaming).** Additive SSE over the htmx poll; it re-renders the settled node via
  the same fragment path, so a freshly-streamed reply will pick up its forward strip on settle like any
  other enrichment. No conflict beyond both touching `replyNode.kte`.
- **Versioning / regenerate (V14).** Covered in §4 — the snapshot is exactly what makes the link robust
  to a source being regenerated or edited.

---

## 9. The second slice (backward / backlinks) — built

Implements §6 over the first slice's edges, **no new schema**.

**Server**
- `QuoteRepository.byTarget(threadId)` — incoming edges grouped by quoted comment (shares one ordered
  read, `edges`, with `bySource`).
- `QuoteScanner` (Tier-0) + the assembler merge **derived** edges (from markdown blockquotes — §5) with the
  stored toolbar edges, de-duped, before grouping — so persona/typed quotes join both directions and
  coalesce with toolbar quotes of the same passage. Forward refs de-dupe by (target, normalized passage);
  backlinks group by normalized passage and distinct quoter.
- `ReplyView.quotedBy: List<QuoteBacklink>` enriched in `ReplyTreeAssembler`: incoming edges grouped by
  exact `quoted_text` (**per-exact-span coalescing**), each `QuoteBacklink(text, quoters)` with
  `QuoteQuoter(commentId, author, snippet-of-quoter-body)`.
- `replyNode.kte` renders `.reply__quoted-by[data-quoted-by-count]` with a `.reply__backlink
  [data-backlink-text]` per passage (full passage in the attribute for client matching; truncated label
  shown) containing quoter `a[data-backlink-src]` links — the no-JS fallback + the JS data source.

**Client**
- `quote-backlinks-core.mjs` (pure, unit-tested): `matchPassage(haystack, needle, fromIndex)` —
  whitespace-tolerant search returning offsets into the original text.
- `quote-backlinks.js` (DOM glue, loaded in `layout.kte`): per comment, locate each passage in a body
  text node and wrap it in `<mark class="quoted">`; hide that passage's SSR fallback entry; on
  hover/focus build a `.quote-cone` popover from the SSR quoters (single-quoter click jumps directly).
  Unlocated passages stay in the visible fallback. Re-runs on `htmx:afterSwap` (guarded per reply).

**Tests:** tier1 `byTarget`; acceptance `comment_quote_backlinks.feature` (quoted-by count, per-exact-span
coalescing, distinct passages, no-backlinks) — all on the SSR block, which is the no-JS contract; the
inline mark + cone are preview-verified. JS `quote-backlinks-core.test.mjs`. `verifyAll` + `npm test`
green; marks/coalescing/cone confirmed live.
