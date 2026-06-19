# AI Forum — UX feedback v2 (current handover) · for Claude Design

> Supersedes `ai-forum-ux-feedback-v1.md` (kept for history). This is the clean, current list: what **v2 nailed** (keep it) and the **two** things left for the next pass — the front-page trim, and the **generation error / retry / cancel** states grouped by how the UX should handle them.

---

## ✅ v2 nailed it — keep these, don't regress

- **Synthetic personas** (maro · vex · sol · pike · lune) — real names gone.
- The thread's **inline-at-node composer** + the persistent bottom **level-0 "reply to the post"** composer.
- **Context-scope defaults** — bottom composer = whole thread; inline composer drives the branch highlight.
- **Dim at 0.6** — out-of-context comments are de-emphasised but still legible.
- **Branch-index jump / smooth-scroll-to-node.**
- **Scribble-friendly composer** — auto-grow, expand-on-focus, capped max-height.
- **New-thread composer + the three empty states** (fresh forum, empty thread, all-caught-up).
- The HUP aesthetic (light **and** dark), the **context-scope-on-tree** with legend + live read-count, and the **+1 / More-of-this / Reply / Ask** action hierarchy.

---

## ① Front page → threads-only (M1)

The front page still shows **poll / blog** content-type chips. Phase 1 (M1) is **threads only** — drop the poll / blog / article types from the index so it's a pure thread list. (The community chrome — ads, login, new-users — is already trimmed; this is just the content types.)

---

## ② Generation error / retry / cancel — the missing states (M1)

Every screen shows only the happy path (drafting → reply). `claude -p` *will* fail, so "drafting…" forever can't be the only outcome. The many technical failures collapse into a **handful of UX patterns** — design these six, not ten separate things.

### A · Node-level "failed → retry"
*Covers: timeout · process crash / non-zero exit · empty output · truncated / malformed output.*
A clearly-failed node (muted, small ⚠ / ✕), a one-line reason, and a **Retry** button. One visual serves all four.

### B · Rate-limited — "wait, then retry"
*Covers: auth invalid · usage cap / 429.*
**Visually distinct from a plain error** so it doesn't read as a bug: "usage limit — try again in ~Nm," with Retry (and, later, an auto-retry countdown). Calm, not alarming.

### C · Cancelled (owner-initiated)
*The Cancel control + its result.*
A **Cancel** affordance on any in-flight draft (including a roomful mid-fan-out), and a **neutral** "cancelled" state afterward — grey, not red; it's a choice, not a failure.

### D · Partial failure inside a roomful
*One persona fails among successes.*
The successful replies post normally; the failed persona shows its **own** failed chip with its **own** Retry — the room is **not** failed as a whole. (Roomful is **sequential** in M1, so failures surface one at a time.)

### E · Couldn't save (persistence)
*Generated fine, the DB write failed.*
"Couldn't save — retry," and crucially **don't lose the drafted text**. Owner-tapped retry is fine for M1.

### F · Pre-send validation
*Empty question / no persona picked.*
An **inline** message in the composer, **before** anything is sent — no wasted call, no node created.

### Retry is manual in M1
Every "Retry" above is **owner-tapped** for M1 — simplest, and it sidesteps a double-post edge case (requirements §4). Auto-retry / back-off with reconciliation is a later refinement, so no need to design countdowns or auto-states yet.

---

## One-line summary
Keep all of v2. Two things left: **front page → threads-only**, and the **six error/retry/cancel states** above — most failures collapse into "failed → retry," with rate-limit, cancel, partial-roomful, persistence, and validation as the genuinely distinct ones.
