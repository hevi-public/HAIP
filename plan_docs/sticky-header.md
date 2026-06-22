# Sticky site header + click-to-scroll-to-top

> **Status:** decided & built · **Owner:** Hevi · **Created:** 2026-06-22
> Touches the shared chrome in `layout.kte` (every page) and `app.css`; companion JS in `header.js` /
> `header-core.mjs`, acceptance in `header.feature`.

## The ask

Two small chrome behaviours on the header (the `~/ AI Forum · threads · members · theme` bar):

1. **Sticky on desktop** — the header pins to the top of the viewport while reading long threads, so the
   nav + theme toggle stay reachable. Mobile keeps a static header (pinning a 48px bar on a short phone
   viewport costs more reading room than it buys).
2. **Click the bare chrome → scroll to top** — clicking the empty header space smooth-scrolls the page
   back up. The real controls (brand link, nav links, theme buttons) keep their own behaviour and must
   **not** also scroll.

## Design

The header is **one source**: it lives in `fragments/siteHeader.kte` and is injected by `layout.kte`, so
every page (home, thread, personas, starred, persona edit) renders the same bar — there are no per-page
copies to drift. The structural hook the JS binds to is `data-scroll-top` on the `<header>`.

**Scroll-to-top — pure core + thin glue** (the same split as nav / persona-form):
- `header-core.mjs` exports `shouldScrollToTop(pathTags)` — given the lowercased tag names walked from the
  clicked node up to the header, it returns `true` only when **no** interactive control
  (`a`, `button`, `input`, `select`, `textarea`, `label`, `summary`) sits in that path. Pure, no DOM,
  unit-tested.
- `header.js` collects the path from the click target, asks the core, and on `true` calls
  `window.scrollTo({ top: 0, behavior })` — `behavior` is `smooth` unless `prefers-reduced-motion: reduce`.
  Event-delegated on `document`, no-ops where `[data-scroll-top]` is absent.

The brand stays a plain link to `/` (decided with Hevi) — scroll-to-top fires only on empty chrome, and
the core already treats the brand `<a>` as interactive, so no special-casing.

## Two gotchas worth remembering

- **Safari needs `-webkit-sticky`.** `position: sticky` worked in Chrome but silently no-op'd in Safari
  until the declaration was written `position: -webkit-sticky; position: sticky`. Without the prefixed
  value first, older Safari/iOS engines drop the *whole* declaration. Applied to the header **and** both
  side rails (`.home__rail`, `.thread__rail`), which are sticky too.
- **Sticky rails must clear the now-pinned header.** The branch-index / forum-nav rails pin with
  `position: sticky`; once the 48px header is itself pinned, a rail at `top: 18px` would slide *under* it.
  So the rails pin at `top: 66px` (48px header + 18px gap) with `max-height: calc(100vh - 84px)`. Change
  the header height and these offsets move with it.

## How it's tested (BDD/TDD)

| Layer | What it pins | Where |
|-------|--------------|-------|
| **JS unit** (`node:test`, tier-0 analogue) | The `shouldScrollToTop` decision — empty chrome scrolls; brand / nav / theme do not; case-insensitive; non-array → no scroll | `src/test/js/header-core.test.mjs` |
| **Acceptance** (HTTP) | Every page renders the `<header data-scroll-top>` hook the glue binds to — checked on the front page **and** a thread page | `src/test/resources/features/header.feature` + `HeaderSteps` |

The sticky CSS itself and the smooth-scroll animation are browser behaviours — not observable over HTTP and
not exercised by a headless Chromium (which no-ops `behavior: "smooth"`). They were verified manually in
the preview by measuring computed `position` / pinned `getBoundingClientRect().top` and by recording the
`scrollTo` call on chrome-vs-control clicks; the Safari prefix fix was confirmed by Hevi on a real Safari.
