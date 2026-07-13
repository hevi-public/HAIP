# Composer branch-context controls — generation scope & include-siblings

> **Status:** proposed (design note, not yet built) · **Owner:** Hevi · **Created:** 2026-06-21
> Companion to `fragments/composer.kte`, `GenerationService.planGeneration`, and the
> `AI Forum - Composer States.dc.html` comp (state 4 · "context-scope control").
> Surfaced while building the composer affordances on branch `claude/reverent-moore-11adc8`.

## Background — what branch scope actually feeds the model today

A branch-scoped reply (`scope = BRANCH_ONLY`) is given **only the vertical ancestor path** of the node
being replied to. From `GenerationService.planGeneration`:

```kotlin
val contextComments = if (scope == ScopeMode.BRANCH_ONLY && parentId != null) {
    val path = comments.ancestorPath(parentId)              // root → … → parent
    if (includeSiblings) {
        (path + comments.childrenOf(parent?.parentId)).distinctBy { it.id }   // + the PARENT's siblings
    } else {
        path
    }
} else {
    comments.threadComments(threadId)
}
```

So for a new reply under `parentId`:

| Mode | What the persona reads |
|---|---|
| `BRANCH_ONLY`, `includeSiblings=false` (today's only browser path) | the ancestor chain root → parent. **No siblings of any kind.** |
| `BRANCH_ONLY`, `includeSiblings=true` | the ancestor chain **+ the reply target's siblings** (`childrenOf(parent.parentId)` = the parallel branches one level up). |
| `WHOLE_THREAD` | the entire thread. |

**The gap that prompted this note:** branch scope never includes the *new comment's own direct
siblings* — the other existing children of `parentId`. Neither mode adds `childrenOf(parentId)`. So if
two personas have already replied under node P and you branch-reply under P again, the third persona
does **not** see the first two. That is intentional vertical isolation (the per-branch context scoping
is the product's differentiator, §5), but it is a surprising default once you notice it, and there is
currently **no UI to widen it** short of switching to whole-topic.

Two distinct "sibling" notions are in play; the current opt-in serves only the first:

- **Reply target's siblings** — other children of `parent.parentId` (the branches alongside the node you
  clicked Reply on). *This* is what `includeSiblings=true` adds today.
- **New comment's direct siblings** — other children of `parentId` (prior replies to the very same
  parent). **Not reachable today** in branch scope.

## Why it's invisible right now

1. **`includeSiblings` is not wired into the composer.** The field exists on `GenerateRequest` and is
   honoured by the service, but the composer rework (chips / Single↔Roomful / slash / @mention) did
   **not** surface it — so every browser branch-reply runs with `includeSiblings=false`.
2. **The visible "context" control is only `routingScope`.** The composer shows a "looking at" select,
   which scopes what the *dispatcher* reads (who replies), **not** the generation `scope` (what the
   chosen persona reads). The generation `scope` is a hidden input, driven only by the `/branch` /
   `/topic` slash commands. The comp's state-4 showed a *visible* "This branch / Whole thread" segmented
   control **with an "include siblings" checkbox** — that control was never built.

## Proposed deliverable

Make branch context legible and adjustable from the composer, and settle the sibling semantics.

1. **Surface the generation-scope control.** Add the comp's visible "context = This branch / Whole
   thread" segmented control bound to the (currently hidden) `scope` field, distinct from the existing
   "looking at" dispatcher control. Keep the `/branch` `/topic` slash commands as the keyboard path
   (they already drive both — see the context-feedback fix on this branch).
2. **Add the include-siblings toggle** next to it (comp state 4), bound to `includeSiblings`, shown only
   when scope is `BRANCH_ONLY` (it's a no-op under whole-topic). Reflect the live "reads N msgs" hint.
3. **Decide what "include siblings" means** — the open question below. At minimum, document the chosen
   semantics in the `planGeneration` KDoc and pin it with a tier-2/acceptance test (the existing
   `context_scoping.feature` is the home for this).

## Open questions / decisions needed

- **Which siblings?** Keep the current "reply target's siblings" (`childrenOf(parent.parentId)`), switch
  to the "new comment's direct siblings" (`childrenOf(parentId)`), or offer both? The comp's wording
  ("root → this branch **+ siblings**") is ambiguous. Recommendation: the most intuitive reading of
  "include siblings" while replying under P is *"also show me the other replies already under P"* →
  `childrenOf(parentId)`. The current behaviour (one level up) is arguably a latent bug, but it is
  load-bearing for the `context_scoping.feature` assertions — **changing it needs a test review**, not a
  silent swap.
- **postAsOwner interaction.** With the composer's owner-message flow, the persona's `parentId` is the
  freshly-posted owner node, whose only child is the persona itself — so `childrenOf(parentId)` would be
  ~empty and `childrenOf(parent.parentId)` resolves to the owner node's siblings. The chosen semantics
  must be defined against the **owner node**, not the originally-clicked node. Verify against the real
  anchor (`anchorId`) the service uses.
- **Default.** Should branch scope default to including direct siblings (friendlier) or stay strictly
  vertical (cleaner isolation, current)? Leaning: keep strict-vertical default, make siblings the
  explicit opt-in — preserves the differentiator, matches the comp's unchecked-by-default checkbox.

## Test coverage to add

- Tier-2 `GenerationServiceTest`: branch scope **excludes** direct siblings by default; with the toggle,
  **includes** exactly the chosen sibling set (assert the context comment ids handed to the seam).
- Acceptance `context_scoping.feature`: a rule pinning the browser-visible behaviour of the new toggle
  end-to-end (mirrors how `persona_routing.feature` pins the "looking at" scope).
