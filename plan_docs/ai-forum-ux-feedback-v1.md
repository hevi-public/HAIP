# AI Forum — v1 mockup feedback & suggestions (for Claude Design)

> ⚠️ **Superseded by `ai-forum-ux-feedback-v2.md`** — the current, clean hand-off. Kept for history only. The v2 doc carries the up-to-date keep-list and the two remaining items (front-page threads-only + the six error/retry/cancel UX states). Don't build from this file.

> Review of the Phase-1 "direction A" set (front page, thread, composer states, components, persona/admin, dark variants).

> **v2 status (2026-06-19, full review):** v2 applied nearly all the feedback. ✅ **Resolved:** synthetic personas (①); the **inline-at-node composer** + a bottom **level-0 "reply to the post"** composer; **context-scope defaults** (bottom = whole thread, inline drives the branch highlight); **dim lightened to 0.6**; **branch-index jump / smooth-scroll-to-node**; the **Scribble-friendly composer** (auto-grow, expand-on-focus, max-height); and the **new-thread composer + three empty states**. 🔵 **Still open:** ② (front page still shows **poll / blog** content-type chips → make it threads-only) and the **generation error / retry / cancel** frame (added *after* this review, so not yet in the mockups).
> **Verdict: strong v1 — keep the direction.** The notes below are targeted refinements, ordered by priority. Most of the set should be preserved as-is.

---

## ✅ Keep — these landed; don't regress them

- **The HUP aesthetic and the sage-green identity** (`#b3bca3` accent, cream/olive, mono chrome) — light *and* dark both read right. Dark mode is a real palette, not an invert.
- **The context-scope-on-tree** — lighting the ancestor path and dimming what the persona won't read, with a **context legend** and the live **read-count preview** ("ritchie reads 4 msgs · root → this branch + siblings"). The read-count line is the standout; it makes the feature legible. Keep it everywhere the scope can change.
- **The branch-index ToC**, collapse/one-liner with hidden-descendant count, and the **mobile focus / re-root** pattern for deep trees.
- **The five composer states**, the **slash palette** (`/branch /thread /roomful /more`) and **@mention** dropdowns, and the **roomful fan-out drafting** state ("⟶ carmack is drafting · claude -p · keep reading").
- **The action hierarchy** from the component sheet: **Ask** (primary), **More of this** (called-out pill), **Reply** (link), **+1** (quiet, recedes). This is exactly the intended treatment.
- **The admin "add member" form** (name → auto-monogram, handle, descriptor, system prompt, signature; `writes ~/personas/{slug}.md`).

---

## ① Swap the persona roster from real people → synthetic archetypes  *(highest priority)*

Every persona is currently a real public figure — Ritchie, Carmack, Stallman, Hopper, Knuth, Turing, Ada, Bjarne, Hannu — and they're baked into the default seed data on every screen.

This conflicts with the product's core design decision: **personas are fully synthetic, inspired-by archetypes — never digital twins of real, named people.** It also means shipping things like "AI Stallman says…", which we specifically want to avoid.

**Please replace the roster with invented personas** that *evoke* an archetype without being the person: an invented name + handle, an expertise descriptor, and a distinct voice. The seed examples should set this pattern, since they'll get copied into the real roster. For instance (illustrative, feel free to improve):

- **Maro** `@maro` — terse C/systems greybeard; "reads your code before your explanation."
- **Vex** `@vex` — perf-obsessed graphics/low-level hacker; benchmarks everything.
- **Lune** `@lune` — compiler & type-theory mind; loves a worked example.
- **Pike** `@pike` — pragmatic distributed-systems veteran.
- **Sol** `@sol` — security/skeptic; assumes everything is hostile input.

Same *flavour* of expert friction the legends gave you — just not the legends.

## ② Trim the front page to Phase 1 + single-user

It cloned HUP a little too literally and pulled in furniture that doesn't apply:

- **Content types:** Phase 1 is **threads only**. Remove articles / blogs / polls (Cikk / Blogbejegyzés / Szavazás) from the index and rails — the front page is a **thread index**.
- **Multi-user / community chrome:** drop **partner ad boxes** (RackForest, szerver.hu), **"follow us"** social, the **"new users"** panel, and **login/logout** — this is a private, single-user tool.
- **Keep** the genuinely useful right-rail panels (recent activity, search) and the `ls -1` left rail (it's charming and on-brand). Keep the look; lose the community furniture.

## ③ Phone front page — already phone-native ✓ (verify the *thread* on mobile)

**Correction to the first review.** The v1 *front page* already has a proper phone-native layout — a clean thread list with type chips, "N new" pills, a single **+ New thread** action, hamburger nav, and a minimal footer, with none of the desktop community chrome. The earlier "squeezed desktop table" note was a misread: it was based on `ref_mobile_view_p1.png`, which is a **reference capture of HUP.hu itself**, not the AI Forum's mobile design. **Keep the phone front page as-is.**

- Still worth checking (a different screen): verify a **long, deeply-nested thread** reads comfortably on the phone — body text, **code blocks**, and per-node actions thumb-friendly. The focus/re-root pattern already helps a lot.
- Note: the mobile front page still shows **poll / blog type chips** (P, B) and **real-person handles** — those are covered by ① and ② below, not a layout issue.

## ④ Inline reply composer at the node; bottom composer always replies to the OP

Right now there's a single bottom composer that re-targets ("Replying to ritchie"). Change the model so placement matches the branching mental model:

- **Direct reply (to any comment):** clicking **Reply** on a node opens the composer **inline, in the tree, exactly where the new reply will appear** (as a child of that node). You compose the reply where it will live — spatially honest.
- **Replying to the post:** the **persistent bottom composer** always targets **level 0** — a top-level reply to the root post. It never re-targets; it's the "add a top-level comment" entry.
- Both composers share the same controls (slash / @mention / context-scope / single-vs-roomful); only the **target** differs (inline = that node; bottom = OP). One inline composer open at a time. On mobile, the inline composer appears at the node within the focused view.
- **Context-scope default:** the **level-0 (bottom) composer defaults to whole thread**; the **inline composer's scope is selectable** (natural start: *this branch*) — let placement and scope reinforce each other without forcing it.
- **Make the field Scribble-friendly:** generous room for **Apple Pencil Scribble** (iPad handwriting) — more space is better. On **tablet/desktop** ~**150%** of the current height (≈ one more line); on **mobile** compact at rest but **expanding on tap/focus**; **auto-grow with content up to a max height**, then scroll.

---

## Smaller refinements (nice-to-have)

- **Required new frame — generation error / retry / cancel:** every screen shows only the happy path (drafting → reply). `claude -p` will fail, so a node stuck on "drafting…" forever can't be the only outcome. This is an **M1** state. Distinct visual states to design:
  - **Failed → retry** (generic: timeout / process error / empty / malformed) — a clear failed node with a **Retry** action.
  - **Rate-limited** — visually distinct from a plain error ("usage limit — try again in N"), so it doesn't read as a bug.
  - **Cancelled** — neutral styling (owner stopped it), not an error; plus a **Cancel** control on an in-flight draft (including a roomful mid-fan-out).
  - **Partial roomful** — one persona's chip failed among successes, retryable on its own, without failing the whole roomful.
  - **Persistence failed** — the reply generated but didn't save → a "couldn't save — retry" affordance (don't lose the text).
  - **Validation** — empty question / no persona selected → inline message in the composer, before anything is sent.
- **② still partly open:** the front page still shows **poll** content-type markers — Phase 1 (M1) is **threads only**, so drop poll/blog/article types from the index.
- **Scope notes for M1 (so you don't over-invest):** **roomful is sequential in M1** (drafting states appear one-after-another, not all at once); **search** is deferred to 1.1 (de-emphasise the front-page search box); **dark mode** stays only if it's free from the tokens; **per-node unread** → M1 ships thread-level "N new" only.

- **Branch index = jump-map:** selecting a row in the branch index should **scroll the thread so that node sits at the top of the view**. Right now a ToC row sets the reply target; make its primary job *navigation* (reply targeting lives on the node's **Reply** button).
- **Dim is too strong:** out-of-context comments (~0.34 opacity in v1) are hard to read. **Lighten the de-emphasis** so a dimmed comment is still legible — clearly not-in-context, but readable (≈0.55–0.65 opacity, or mute via reduced contrast/colour rather than heavy transparency).
- **Consistency:** the slash palette lists `/branch /thread /roomful /more` in Composer States but drops `/thread` in the Components sheet — keep the command set identical across screens.
- **Persona profile metric:** the profile surfaces "47 ▸ more of this received." "More of this" is legitimately visible, but a public per-persona *count* edges toward the reputation metric we deliberately cut. Consider making it **owner-private** (or dropping it) so it doesn't become a popularity score.
- **Missing frames the brief asked for:** a **new-thread composer** (top-level, not a reply) and **empty states** (fresh forum, empty thread, no-unread) — quick to add, useful for the build.
- **Stress frame:** one **code-heavy** post with syntax highlighting in a deep branch, on both desktop and mobile, to pressure-test density.

---

## One-line summary for the next pass
Keep the look, the context-scope tree, the phone-native front page, and the composer — **swap the real-person personas for synthetic archetypes, strip the front page back to Phase-1 threads-only/single-user, and move direct-reply composing inline to the node (bottom box = reply to the post).**
