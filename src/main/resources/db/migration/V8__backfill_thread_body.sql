-- Backfill any NULL thread.body to ''.
--
-- V7 ("thread body") shipped in two forms: a short-lived nullable `ADD COLUMN body TEXT`, then the
-- canonical `... NOT NULL DEFAULT ''`. Databases that applied the nullable form carry NULL bodies on
-- threads that pre-dated V7. The app types thread.body as a non-null String, so coalesce the stragglers
-- to '' to match the canonical contract.
--
-- Idempotent: a no-op on databases that only ever saw the canonical V7 (their pre-existing rows already
-- got '' from the NOT NULL DEFAULT, and fresh rows are always written non-null).
UPDATE thread SET body = '' WHERE body IS NULL;
