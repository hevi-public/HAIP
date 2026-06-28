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

## 5. On "do we need a quote type?"

No type discriminator. The meaningful distinction is **linked (has an edge) vs. plain blockquote (no
edge)** — not manual-vs-menu:

- A **menu quote** creates an edge *by construction* (we know the source comment + the selected text).
- A **manual `> blockquote`** is just markdown. We cannot reliably infer which comment it came from
  (text alone is ambiguous / may be original prose), so it gets no edge and renders as a plain
  blockquote.

So we don't persist a "manual"/"menu" flag. Everything is **derived from edge presence**: a blockquote
with a matching edge is a linked quote; one without is plain. If we later want to let the owner promote a
manual blockquote into a linked quote, that's an affordance that *creates an edge* — still no type field.

---

## 6. Deferred: backward refs, coalescing, the selector cone

The richer half of the original idea, deferred to a follow-up slice so the forward half can ship and
prove the model:

- **Backlinks.** For each comment, the set of edges where it is the `target`. Surfaced minimally as a
  "quoted by N" affordance even when the inline span can't be re-found (so it survives edits).
- **Inline marker + coalescing.** Best-effort re-find each `quoted_text` in the rendered body, wrap it
  in `<mark class="quoted">`. When several edges target the **same passage**, coalesce to a **single**
  marker. Open question to settle when we build it: coalescing granularity —
  - *per-comment* (simplest: the whole comment shows "quoted by N", one marker on a best-effort span),
  - *per-exact-span* (coalesce only identical `quoted_text`),
  - *per-overlapping-region* (merge overlapping spans into one union marker — richest, closest to the
    original "all quoted sources should turn into one", hardest to do over rendered HTML).
  Recommend starting per-exact-span and revisiting overlap merging only if real usage needs it.
- **Selector cone.** Hovering a coalesced marker reveals a popover listing the quoters (author monogram
  + snippet + jump link), clicking navigates. This reuses the existing popover idiom (slash palette /
  @mention menu) and the branch-index rail's jump-link pattern. "Cone" = the fan-of-options affordance;
  the exact visual (radial fan vs. simple list popover) is a UX call for that slice.
- **Decorated blockquote.** Once the text-matching exists, the forward direction can decorate the inline
  `<blockquote>` itself instead of (or in addition to) the separate strip.
- **Admin / graph view.** A citation overview on `/admin` (most-quoted comments, dangling edges) is a
  natural later read-only addition — all the data is in `comment_quote`.

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

**Out of scope for this slice:** everything in §6 (backlinks, coalescing, selector cone, decorated
blockquote, admin graph) and the robust text-selector anchoring in §4.

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
