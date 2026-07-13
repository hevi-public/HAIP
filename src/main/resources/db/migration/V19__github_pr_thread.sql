-- Maps an ingested GitHub pull request to the forum thread created for it (plan_docs/github-pr-threads.md).
-- (V19 follows V18 = comment_quote. It was numbered V19 to dodge a duplicate-V18 collision with the
-- comment-quotes branch, now merged — so the chain is contiguous V17 → V18 → V19.) The "Discuss this PR"
-- button creates one thread per PR;
-- this table makes that idempotent — a second click finds the existing thread instead of creating a
-- duplicate, and the /github page shows "View thread" rather than "Discuss" for an already-ingested PR.
--
-- repo is the "OWNER/REPO" the PR belongs to (the configured aiforum.github.repo; "" when gh infers it from
-- the working dir — fine for the single-repo PoC). head_sha is captured at ingest time for a future
-- "PR got new commits → append an update note" re-sync (not read on any path yet). thread_id references
-- thread(id) with foreign_keys=on, so a thread delete must clear its mapping row first.

CREATE TABLE github_pr_thread (
    id         TEXT    PRIMARY KEY,
    repo       TEXT    NOT NULL,
    pr_number  INTEGER NOT NULL,
    thread_id  TEXT    NOT NULL REFERENCES thread(id),
    head_sha   TEXT,
    created_at TEXT    NOT NULL,
    UNIQUE(repo, pr_number)
);

CREATE INDEX idx_github_pr_thread_thread_id ON github_pr_thread(thread_id);
