# GitHub PR → forum thread ("Discuss this PR")

> **Status:** ✅ Slice 1 built (2026-06-28) · Slice 2 (discussion ingest) pending · **Owner:** Hevi · **Created:** 2026-06-28
> Turn a GitHub pull request into a forum thread so the room (the hand-authored personas) summarises
> *what changed* and discusses it. Builds directly on the read-only GitHub seam from
> `plan_docs/github-integration.md` and the create-time "Whole Topic + Anyone" auto-summon
> (`ThreadController.newThread`, see the OP-node model).
>
> **Slice 1 shipped:** `GitHubClient.pull()` (gh pr view + diff, read-only allowlist widened to
> `pr → {list,view,diff}`); `PrThreadFormat` (Tier-0 OP builder, 300-line diff budget); V19
> `github_pr_thread` mapping + `GitHubPrThreadRepository` (idempotent; V18 is the comment-quotes branch's);
> `POST /github/pr/{n}/discuss` + the Discuss/View-thread button on `/github`. `verifyAll` + the new
> `github_pr_thread.feature` green; prod-profile boot smoke-tested.

## Goal

From the `/github` page, the owner clicks **Discuss** on an open PR. The app fetches the PR (description,
changed-file stats, diff, and — Slice 2 — the existing review/comment discussion), creates a thread whose
**opening post carries the PR**, and fires the existing auto-summon so the dispatcher routes the right
persona(s) to **summarise the change and discuss it**. A PR maps to exactly one thread; a second click
lands on the existing thread.

This is "an agent summarises what changed" with almost no new machinery: the opening post already feeds
the room (`GenerationService.withOpeningPost`), and the "Anyone" dispatcher already picks who replies. The
new work is (a) fetching one PR in depth, (b) rendering it as an opening post, (c) idempotency, and (d) the
button.

## Decisions (owner, 2026-06-28)

- **Trigger: manual button on `/github`.** No background polling, no webhook. Simplest first slice; the
  ingestion service it produces is exactly what a future `@Scheduled` poll (cf. the backup scheduler) or a
  webhook endpoint would call. The owner chooses which PRs are worth a thread.
- **Depth: full — description + changed files + diff + review/comment discussion.** Sliced so the diff
  lands first and the discussion follows.
- **Summariser: the existing auto-summon room.** The PR lands in the opening post; the create-time
  "Whole Topic + Anyone" call routes the summary to whichever persona fits (Sol/Paul/…). No dedicated,
  deterministic "GitHub bot" summary — the summary *is* a persona reply, like every other thread.

## Architecture — reuse over invention

Everything hangs off patterns already in the tree:

| Concern | Reused mechanism |
| --- | --- |
| Fetch GitHub data | the `GitHubClient` read-only seam (`GhCliGitHubClient` shells `gh`) |
| Pure parsing of `gh --json` | `GitHubJson` (Tier-0) |
| Create a thread that summons the room | `ThreadRepository.insert` + `GenerationService.summonAsync(AUTO_PERSONA, WHOLE_THREAD)` |
| Render PR markdown safely | `MarkdownRenderer` (commonmark + highlight.js, `escapeHtml(true)` firewall) |
| Test double | `ScriptableGitHubClient` (`@Primary @Profile("test")`), reset in `DatabaseResetHooks` |

### New pieces

1. **Seam extension — `GitHubClient.pull(number): PullResult`.** `gh pr view <n> --json …` for the
   description + changed-file stats + head SHA, plus `gh pr diff <n>` for the unified diff. Both are reads.
   The `requireReadOnly` allowlist widens from one-subcommand-per-verb (`Map<String,String>`) to
   `Map<String,Set<String>>` so `pr` may be `list`, `view`, or `diff` — still no mutating verb can ever be
   built. `PullDetail` carries `(number, title, author, url, state, isDraft, body, baseRef, headRef,
   headSha, changedFiles, diff)`.

2. **`PrThreadFormat` (Tier-0, pure).** Builds the thread title (`#<n> — <title>`) and the opening-post
   **markdown**: the PR description, a `## Changed files` list (`path` · +adds/−dels), and a fenced
   ```diff block truncated to a line budget (a "diff truncated to N lines" note when clipped, with a link
   to the PR for the full view). Pure string-building → fully unit-tested; the diff budget keeps a giant PR
   from blowing the model's context window.

3. **Idempotency — V19 `github_pr_thread`.** `(repo, pr_number) → thread_id`, `head_sha`, `UNIQUE(repo,
   pr_number)`. `head_sha` is stored now for a future "PR got new commits → append an update note" sync
   (deferred). `GitHubPrThreadRepository`: `insert`, `findByPr`, `threadIdsByNumbers` (so the `/github`
   page can show **View thread** instead of **Discuss** for already-ingested PRs).

4. **`GitHubPrIngestionService`.** The orchestration: `findByPr` → if present return `Existing(threadId)`
   (no `gh` call); else `github.pull(n)` → on `Unavailable` return the reason; on `Ok` insert the thread
   with the formatted OP, record the mapping, fire `summonAsync`, return `Created(threadId)`. (Slice 2:
   insert the PR's comments as nodes *before* the summon so the room reads the discussion.)

5. **Trigger — `POST /github/pr/{n}/discuss`** on `GitHubController` → ingest → PRG redirect onto the
   thread (or back to `/github` if the PR fetch was unavailable). The `/github` row renders a **Discuss**
   button (POST form) when un-ingested, or a **View thread** link when already mapped.

## Slices

- **Slice 1 ✅ (built 2026-06-28): PR → thread.** Seam `pull()`, `PrThreadFormat`, V19 + repo, ingestion
  service, the Discuss/View-thread button, auto-summon. The OP carries description + changed files + diff.
  No comments yet.
- **Slice 2: review & comment discussion.** Extend `pull()` with `comments`/`reviews` (same `gh pr view`
  call); post each as a node authored by `gh:<login>` (a non-persona author, rendered like `owner`/`system`
  with a per-login monogram hue) under the OP, inserted before the summon. Adds the "team-mate discussion"
  the owner asked for.

## Deferred (designed, not built)

- **Live re-sync.** A PR gains commits/comments after ingest. `head_sha` is stored so a future poll can
  detect a changed head and append an update node; Slice 1 is a point-in-time snapshot.
- **Inline review-line comments.** Line-level review threads need `gh api repos/…/pulls/…/comments` (a GET,
  but it widens the allowlist past subcommand-granularity); deferred unless line-level threads are wanted.
- **Scheduled polling / webhook.** Both call the same `GitHubPrIngestionService.ingest`. Polling reuses the
  backup `@Scheduled` pattern and needs a high-water mark so enabling it doesn't back-fill every open PR.

## Trust note

PR titles, descriptions, and comments are **untrusted external input** (arbitrary GitHub contributors).
They are XSS-safe on render (`MarkdownRenderer`'s `escapeHtml(true)` firewall, same as the existing
`/github` page), and feeding them to the model is just text. A hostile PR/comment could attempt
prompt-injection against the summarising persona; this is **accepted** for a single-user local-first PoC
(no sanitisation built). Worth revisiting if the forum ever ingests PRs from an untrusted public repo.

## Test plan (tiered)

- **Tier-0:** `PrThreadFormatTest` (title, body sections, diff truncation, blank description) +
  `GitHubJsonTest.parsePull`.
- **Tier-1:** `GitHubPrThreadRepositoryTest` (insert / findByPr / UNIQUE / threadIdsByNumbers — leaves no
  rows, per the FK-isolation convention) + extend `GhCliGitHubClientTest` for the `pr view` / `pr diff`
  argv and the widened read-only invariant.
- **Tier-2:** `GitHubPrIngestionServiceTest` over a fake `GitHubClient` + in-memory repo subclasses —
  idempotency short-circuit (no `gh` call), unavailable (no thread), created (thread + mapping + formatted
  OP).
- **Acceptance:** `github_pr_thread.feature` — program `ScriptableGitHubClient` with a PR + script the LLM;
  POST the discuss endpoint; assert the thread's OP carries the diff and a persona summary settles; a
  second discuss redirects to the same thread.
